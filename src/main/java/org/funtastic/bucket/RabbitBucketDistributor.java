package org.funtastic.bucket;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.LongString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.funtastic.bucket.RabbitBucketDistributor.require;

/**
 * Makes use of rabbit queues for coordinated distribution of hash buckets.
 *
 * <h3>What problem is this solving?</h3>
 *
 * <p>One example is a scheduler application. A scheduler is fundamentally
 * a while loop that iterates over a set of tasks, filtering by those that
 * are ready to be performed. It is a singleton, and can become a bottleneck.
 * One way to scale it is to hash all the items into buckets such that they
 * can be grouped together by their bucket. N schedulers can then run against
 * N buckets that are assigned specifically to them, without running over each
 * other or causing data contention.</p>
 *
 * <p>1:1 assignment between schedulers and buckets is easy to implement, and works
 * well until you need to add more schedulers. With this design a rehash is
 * required which can be expensive and cause service interruptions depending on
 * the implementation.  Consistent hashing provides a way to add schedulers
 * after the fact without requiring a re-hash. It does this by hashing many more
 * buckets than schedulers, and then assigning those buckets to schedulers.</p>
 *
 * <p>At this point we can add new schedulers, but we have a configuration problem.
 * We must now maintain configuration as to which scheduler servers are assigned
 * to which buckets. Also, if one (or more) of the scheduler servers dies, we
 * will need to reassign its buckets to other schedulers. With only a handful of
 * scheduler servers, this might not be a big deal, but with many this can be a
 * maintenance nightmare.</p>
 *
 * <p>At last, we come to the point of all this. This BucketDistributor
 * implementation uses rabbit to efficiently distribute hash buckets across
 * an arbitrary number of scheduler servers (or whatever the use case)
 * automatically based on the number of buckets available and the number of
 * scheduler servers active. If the number of active scheduler servers changes,
 * the bucket partitions sizes are automatically adjusted such that each
 * scheduler still receives <code>buckets / schedulers</code> number of buckets.
 * This is designed to work even if the scheduler servers are shutdown
 * uncleanly (think <code>kill -9</code>).</p>
 *
 * <h3>Implementation Notes</h3>
 *
 * <p>Note that the buckets are pushed to clients before they ask for them. For
 * the duration that this distributor is active, it will continue to accumulate
 * buckets up to the specified limit. It will retain them until release() is
 * called, so use cases will generally involve a polling loop to check the
 * contents of buckets(), handle them, and release() them.</p>
 *
 * <p>Because this class calls basicReject() on buckets to put them back into
 * the bucket queue, it is likely that the same distributor instance may
 * receive the same set of specific buckets repeatedly. However, this is not
 * guaranteed, and you shouldn't base any code on the assumption that a
 * distributor always gets the same partition's worth of buckets. A partition
 * is just the maximum number of buckets this class expect to receive at once.</p>
 *
 * <p>This class is threadsafe.</p>
 *
 * <h3>The Perils of Distributed Computing</h3>
 *
 * <p>As this uses rabbit, this distributor offers no guarantees in the face of
 * a network partitions. It would be easy for a scheduler server from my earlier
 * example to receive a partition of buckets and then lose its connection to
 * rabbit. In this scenario, while it is processing its partition, from rabbit's
 * perspective the unacked messages go back onto the queue. Then, another scheduler
 * still connected to rabbit receives those same buckets and begins processing them
 * also.</p>
 *
 * <p>This issue can be mitigated by making processing work against a partition
 * take as little time as possible, or processing in smaller batches. That way
 * the processor spends as little time as possible in its critical section
 * and any damage from this scenario would be minimized.</p>
 *
 * <p>If CP is what you're looking for, go back, this isn't what you want.
 * Try Zookeeper.</p>
 *
 * <h3>Why use this instead of Zookeeper?</h3>
 *
 * <p>Zookeeper is strongly consistent (CP). You want this if you want to
 * guarantee(ish) that your data will never go sideways, at the cost of becoming
 * unavailable in certain circumstances. Consistency is expensive, and not all
 * distributed consensus has to be this precise.</p>
 *
 * <p>This implementation has two big advantages. First, its available rather
 * than consistent. Network partitions don't keep any apps that can connect
 * to rabbit from doing something sensible. Second, its fast. Rabbit fast.
 * 18k buckets/sec on my personal laptop fast. if that tickles your fancy,
 * this may be for you.
 * </p>
 *
 * <h3>Re-Hashing</h3>
 *
 * <p>If you need to completely rehash your data, delete the bucketQueue
 * , update the constructor with the new buckets, and redploy your code. On start(), this class
 * automatically creates the bucketQueue if it doesn't exist and populates
 * it with the configured defaults. This is done using an exclusive queue as
 * a mutex such that it shouldn't happen more than once or on more than one
 * distributor at a time.</p>
 *
 * <p>If you want to inspect the contents of the bucketQueue without having
 * to shut down all the consuming client applications in a cluster in order
 * to do this, there is a simple Command-and-Control api you can use. Post
 * a message to the <b>broadcastExchange</b> containing 'pause', and you will
 * be able to temporarily disconnect all the consumers from the bucketQueue
 * until you're done fussing with it. When you're done, submit another
 * message to that exchange containing 'resume', and you'll be back
 * on your way.</p>
 *
 * <h3>Remaining Work</h3>
 *
 * <ul>
 *     <li>TODO: publish telemetry</li>
 *     <li>TODO: what do alerts and ops dashboards look like for a system like this?</li>
 *     <li>TODO: support hot reloading of config parameters</li>
 *     <li>TODO: is it worth making the buckets a configurable item that can be swapped out without code change?</li>
 * </ul>
 *
 */
public class RabbitBucketDistributor implements BucketDistributor {

    /*
     * Immutable configuration.
     */

    private final Logger log;
    private final Connection c;
    private final String ownerQueue;
    private final String bucketQueue;
    private final String broadcastExchange;
    private final Set<String> defaultBuckets;
    private final ScheduledExecutorService scheduler;
    private final long announcePeriod;
    private final TimeUnit announceUnits;
    private final long expirationPeriod;
    private final TimeUnit expirationUnits;
    private final long partitionUpdatePeriod;
    private final TimeUnit partitionUpdateUnits;
    private final String peerId = peerId();

    /*
     * Mutable state initialized in start().
     */

    private BucketConsumer bucketConsumer;
    private BroadcastConsumer broadcastConsumer;
    private Future announce, partitionUpdate;

    /*
     * Mutable state protected by a lock.
     */

    private final Lock mutex = new ReentrantLock();
    private boolean started;
    private int partitionSize;
    private final Map<String, Long> peers = new HashMap();

    public RabbitBucketDistributor(Connection c, String name, Set<String> defaultBuckets,
                                   ScheduledExecutorService scheduler,
                                   long announcePeriod, TimeUnit announceUnits,
                                   long expirationPeriod, TimeUnit expirationUnits,
                                   long partitionUpdatePeriod, TimeUnit partitionUpdateUnits) {

        log = LoggerFactory.getLogger(RabbitBucketDistributor.class.getName() + "[" + name + "]");

        this.c = c;
        this.ownerQueue = name + ".bucket.owner";
        this.bucketQueue = name + ".bucket";
        this.broadcastExchange = name + ".bucket.broadcast";
        this.defaultBuckets = new HashSet(defaultBuckets);
        this.scheduler = scheduler;
        this.announcePeriod = announcePeriod;
        this.announceUnits = announceUnits;
        this.expirationPeriod = expirationPeriod;
        this.expirationUnits = expirationUnits;
        this.partitionUpdatePeriod = partitionUpdatePeriod;
        this.partitionUpdateUnits = partitionUpdateUnits;
    }

    static void require(Object...args) {
        int count = 0;
        for (Object o : args) {
            if (o == null) {
                throw new IllegalArgumentException("Argument [" + count + "] is null: " + args);
            }
            count++;
        }
    }

    private static BasicProperties props() {
        return new BasicProperties.Builder()
            .deliveryMode(1)
            .contentType("text/plain")
            .build();
    }

    private static BasicProperties broadcastProps(String peerId) {

        Map<String, Object> headers = new HashMap();
        headers.put(BroadcastConsumer.PEER_ID_HEADER, peerId);

        return new BasicProperties.Builder()
            .deliveryMode(1)
            .contentType("text/plain")
            .headers(headers)
            .build();
    }

    /**
     * If nobody owns the exclusive global lock queue, obtain it.
     * If the bucket distribution queue by the same name does not exist, create and populate it.
     * Then delete the global lock queue as we no longer need it.
     */
    private void initBuckets() throws IOException {
        Channel ch = null;
        try {

            ch = c.createChannel();

            boolean owner = false;
            try {
                ch.queueDeclare(ownerQueue, false, true, false, new HashMap());
                owner = true;
            } catch (IOException e) {
                ch = c.createChannel(); // channel is now closed
            }

            if (owner) {

                if (log.isInfoEnabled()) {
                    log.info("acquired owner: " + ownerQueue);
                }

                boolean exists = true;
                try {
                    ch.queueDeclarePassive(bucketQueue);
                } catch (IOException e) {
                    exists = false;
                    ch = c.createChannel(); // channel is now closed
                }

                if (!exists) {

                    if (log.isInfoEnabled()) {
                        log.info("queue does not exist, creating it and seeding it: " + bucketQueue + ": " +
                            defaultBuckets);
                    }

                    Map<String, Object> args = new HashMap();
                    ch.queueDeclare(bucketQueue, false, false, false, args);

                    for (String bucket : defaultBuckets) {
                        ch.basicPublish("", bucketQueue, props(), bucket.getBytes());
                    }
                }

                ch.queueDelete(ownerQueue);

                if (log.isInfoEnabled()) {
                    log.info("released owner: " + ownerQueue);
                }
            }
        }
        finally {
            if (ch != null) {
                try {
                    ch.close();
                }
                catch (IOException e) {
                    // this is ok
                }
            }
        }
    }

    private void updatePartitionSize() throws IOException {
        mutex.lock();
        try {
            int knownConsumers = peers.size();

            int size;
            if (knownConsumers == 0) {
                size = 1;
            }
            else {
                size = defaultBuckets.size() / knownConsumers;
            }

            if (size != partitionSize) {
                if (log.isInfoEnabled()) {
                    log.info("detected " + knownConsumers + " consumer(s), using bucket partition size of " +
                        size);
                }

                try {
                    bucketConsumer.updateQos(size);
                    if (started) {
                        bucketConsumer.restart();
                    }
                }
                catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }

                partitionSize = size;
            }
        }
        finally {
            mutex.unlock();
        }
    }

    private void announce(String name) throws InterruptedException, IOException {
        mutex.lock();
        try {
            long now = System.currentTimeMillis();
            if (!peers.containsKey(name)) {
                if (log.isInfoEnabled()) {
                    log.info("peer added: " + name + (peerId.equals(name) ? " (self)" : ""));
                }
                peers.put(name, now);
            }
            else {
                peers.put(name, System.currentTimeMillis());
            }
        }
        finally {
            mutex.unlock();
        }
    }

    private void retract(String name) throws InterruptedException, IOException {
        mutex.lock();
        try {
            if (peers.containsKey(name)) {
                if (log.isInfoEnabled()) {
                    log.info("peer removed: " + name);
                }
                peers.remove(name);
            }
        }
        finally {
            mutex.unlock();
        }
    }

    private void expire() throws InterruptedException, IOException {
        mutex.lock();
        try {
            long now = System.currentTimeMillis();
            long oldestPermitted = now - expirationUnits.toMillis(expirationPeriod);

            List<String> removeMe = new ArrayList();
            for (Map.Entry<String, Long> e : peers.entrySet()) {

                if (e.getValue() < oldestPermitted) {
                    if (log.isInfoEnabled()) {
                        log.info("peer expired: " + e.getKey());
                    }
                    removeMe.add(e.getKey());
                }
            }

            for (String remove : removeMe) {
                peers.remove(remove);
            }
        }
        finally {
            mutex.unlock();
        }
    }

    private void broadcast(String... msg) throws IOException {
        Channel ch = null;
        try {
            ch = c.createChannel();
            for (String m : msg) {
                ch.basicPublish(broadcastExchange, "", broadcastProps(peerId), m.getBytes());
            }
        }
        finally {
            if (ch != null) {
                ch.close();
            }
        }
    }

    private static final String
        MSG_ANNOUNCE = "announce",
        MSG_RETRACT = "retract",
        MSG_POLL = "poll",
        MSG_PAUSE = "pause",
        MSG_RESUME = "resume";

    private static String peerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "/" + UUID.randomUUID().toString();
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void start() throws IOException {
        mutex.lock();
        try {
            if (started) {
                throw new RuntimeException("already started");
            }

            initBuckets();

            bucketConsumer = new BucketConsumer(log, c, bucketQueue, 1);
            updatePartitionSize();
            bucketConsumer.start();

            broadcastConsumer = new BroadcastConsumer(log, c, broadcastExchange, new BroadcastHandler() {
                @Override
                public void handle(String senderId, String msg) {

                    if (!RabbitBucketDistributor.this.peerId.equals(senderId)) {
                        if (log.isDebugEnabled()) {
                            log.debug("received: " + msg);
                        }
                    }

                    if (msg == null) {
                        return;
                    }

                    if (msg.startsWith(MSG_ANNOUNCE)) {
                        try {
                            announce(msg.split(":")[1]);
                        } catch (Exception e) {
                            log.error("failed to update partition size", e);
                        }
                    } else if (msg.startsWith(MSG_RETRACT)) {
                        try {
                            retract(msg.split(":")[1]);
                        } catch (Exception e) {
                            log.error("failed to update partition size", e);
                        }
                    } else if (msg.startsWith(MSG_POLL)) {
                        try {
                            broadcast(MSG_ANNOUNCE + ":" + peerId);
                        } catch (Exception e) {
                            log.error("failed to broadcast peerId", e);
                        }
                    } else if (MSG_PAUSE.equals(msg)) {
                        log.info("pausing bucket consumer");
                        try {
                            bucketConsumer.stop();
                        } catch (Exception e) {
                            log.error("failed to pause bucket consumer", e);
                        }
                    } else if (MSG_RESUME.equals(msg)) {
                        log.info("resuming bucket consumer");
                        try {
                            bucketConsumer.start();
                        } catch (Exception e) {
                            log.error("failed to resume bucket consumer", e);
                        }
                    }
                }
            });
            broadcastConsumer.start();

            /**
             * Ask everyone to announce themselves for us as we just signed on
             */
            broadcast(MSG_POLL);

            announce = scheduler.scheduleAtFixedRate(new Runnable() {
                public void run() {
                    try {
                        broadcast(MSG_ANNOUNCE + ":" + peerId);
                        expire();
                    } catch (Exception e) {
                        log.error("broadcast failed", e);
                    }
                }
            }, 5, announcePeriod, announceUnits);

            partitionUpdate = scheduler.scheduleAtFixedRate(new Runnable() {
                public void run() {
                    try {
                        updatePartitionSize();
                    } catch (Exception e) {
                        log.error("partition update failed", e);
                    }
                }
            }, 5, partitionUpdatePeriod, partitionUpdateUnits);

            started = true;
        }
        finally {
            mutex.unlock();
        }
    }

    public void stop() throws IOException {
        mutex.lock();
        try {
            if (!started) {
                throw new RuntimeException("already started");
            }

            announce.cancel(true);
            partitionUpdate.cancel(true);

            broadcastConsumer.stop();
            bucketConsumer.stop(); // this waits until the client explicitly releases all the active buckets

            broadcast(MSG_RETRACT + ":" + peerId);

            started = false;
        } catch (Exception e) {
            log.error("error on stop()", e);
        }
        finally {
            mutex.unlock();
        }
    }

    /*
     * public api
     */

    @Override
    public Set<String> buckets() {
        return bucketConsumer.buckets();
    }

    @Override
    public void release(Set<String> names) {
        bucketConsumer.release(names);
    }
}

class BucketConsumer {

    /**
     * A bucket is just a string, this is used to match the strings
     * with deliveryTags so we can basicReject() them as needed.
     */
    private static class Bucket {
        final String name;
        final long deliveryTag;

        Bucket(String name, long deliveryTag) {
            this.name = name;
            this.deliveryTag = deliveryTag;
        }
    }

    private final Logger log;
    private final Connection c;
    private final String queueName;

    /**
     * This lock protects the entire following set of mutable state.
     */
    final Lock mutex = new ReentrantLock();

    /**
     * This condition allows for us to drain the active buckets completely on shutdown
     * before we stop the consumer and close the channel.
     */
    final Condition noneActive = mutex.newCondition();

    private boolean started;
    private Channel ch;
    private String consumerTag;
    private boolean pauseIncomingBuckets;
    private final BlockingQueue<Bucket> incoming;
    private final Map<String, Bucket> active;
    private int qos;

    public BucketConsumer(Logger log, Connection c, String queueName, int initialQos) {
        require(log, c, queueName);
        this.log = log;
        this.c = c;
        this.queueName = queueName;
        this.incoming = new LinkedBlockingQueue();
        this.active = new HashMap();
        this.qos = initialQos;
    }

    public void start() throws IOException {
        mutex.lock();
        try {

            ch = c.createChannel();
            ch.basicQos(qos);

            if (started) {
                throw new RuntimeException("already started");
            }

            consumerTag = ch.basicConsume(queueName, false, new DefaultConsumer(ch) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope, BasicProperties properties, byte[] body)
                    throws IOException {
                    try {
                        incoming.put(new Bucket(new String(body), envelope.getDeliveryTag()));
                    } catch (InterruptedException e) {
                        log.info("interrupted, rejecting delivery", e);
                        ch.basicReject(envelope.getDeliveryTag(), true);
                    }
                }
            });

            pauseIncomingBuckets = false;

            started = true;
        }
        finally {
            mutex.unlock();
        }
    }

    public Set<String> buckets() {
        mutex.lock();
        try {
            if (!pauseIncomingBuckets) {
                for (Bucket b = incoming.poll(); b != null; b = incoming.poll()) {
                    active.put(b.name, b);
                }
            }
            return new HashSet(active.keySet());
        }
        finally {
            mutex.unlock();
        }
    }

    public void release(Set<String> names) {

        if (names.isEmpty()) { // if we are stopped(), this is as far as the client should be able to go
            return;
        }

        mutex.lock();
        try {
            for (String name : names) {
                Bucket b = active.get(name);
                if (b != null) {
                    try {
                        ch.basicReject(b.deliveryTag, true);
                    } catch (IOException e) {
                        throw new RuntimeException("cannot release bucket: " + name, e);
                    }
                    active.remove(name);
                }
            }
            if (active.isEmpty()) {
                noneActive.signal();
            }
        }
        finally {
            mutex.unlock();
        }
    }

    public void stop() throws IOException, InterruptedException  {
        mutex.lock();
        try {

            if (!started) {
                throw new RuntimeException("not started");
            }

            // don't expose any new buckets to the client, even if we have them already in the incoming queue
            pauseIncomingBuckets = true;

            // wait for the client to release all the active buckets
            while (active.size() > 0) {
                noneActive.await();
            }

            // stop the consumer
            ch.basicCancel(consumerTag);

            // get rid of any old incoming buckets locally
            incoming.clear();

            // ask rabbit to send any unacked buckets to someone else
            ch.basicRecover(true);

            ch.close();

            started = false;
        }
        finally {
            mutex.unlock();
        }
    }

    public void updateQos(final int qos) throws IOException, InterruptedException {
        mutex.lock();
        try {
            this.qos = qos;
        }
        finally {
            mutex.unlock();
        }
    }

    public void restart() throws IOException, InterruptedException {
        mutex.lock();
        try {
            stop();
            start();
        }
        finally {
            mutex.unlock();
        }
    }
}

interface BroadcastHandler {
    void handle(String peerId, String msg);
}

class BroadcastConsumer {

    private final Logger log;
    private final Connection c;
    private final String exchange;
    private final BroadcastHandler handler;

    /**
     * This lock protects the entire following set of mutable state.
     */
    final Lock mutex = new ReentrantLock();

    private boolean started;
    private Channel ch;
    private String consumerTag;

    public BroadcastConsumer(Logger log, Connection c, String exchange, BroadcastHandler handler) {
        require(log, c, exchange, handler);
        this.log = log;
        this.c = c;
        this.exchange = exchange;
        this.handler = handler;
    }

    public static final String PEER_ID_HEADER = "peerId";

    public void start() throws IOException {
        mutex.lock();
        try {

            if (started) {
                throw new RuntimeException("already started");
            }

            ch = c.createChannel();
            ch.basicQos(10);

            ch.exchangeDeclare(exchange, "fanout");
            String broadcastQueue = ch.queueDeclare().getQueue();
            ch.queueBind(broadcastQueue, exchange, "");

            consumerTag = ch.basicConsume(broadcastQueue, false, new DefaultConsumer(ch) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope, BasicProperties properties, byte[] body)
                    throws IOException {

                    String msg = new String(body);
                    try {
                        LongString s = (LongString)properties.getHeaders().get(PEER_ID_HEADER);
                        handler.handle(new String(s.getBytes()), msg);
                    }
                    catch (Exception e) {
                        log.error("error handling broadcast: " + msg, e);
                    }
                    finally {
                        ch.basicAck(envelope.getDeliveryTag(), false);
                    }
                }
            });

            started = true;
        }
        finally {
            mutex.unlock();
        }
    }

    public void stop() throws IOException, InterruptedException  {
        mutex.lock();
        try {

            if (!started) {
                throw new RuntimeException("not started");
            }

            // stop the consumer
            ch.basicCancel(consumerTag);

            // close the channel
            ch.close();

            started = false;
        }
        finally {
            mutex.unlock();
        }
    }
}



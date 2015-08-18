package org.funtastic.bucket;

import com.mongodb.Cursor;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.QueryBuilder;
import com.mongodb.WriteConcern;
import com.mongodb.WriteResult;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Consumer {

    private static final long start = System.currentTimeMillis();
    private static final AtomicInteger count = new AtomicInteger(0);

    private static void inc() {

        int v = count.addAndGet(100);

        if (v % 5000 == 0) {
            long now = System.currentTimeMillis();
            long duration = now - start;
            double rate = v / (1d * duration / 1000);
            System.out.println("rate: " + rate + "/sec");
        }
    }

    public static void main(String[] args) throws Exception {

        ConnectionFactory cf = new ConnectionFactory();
        cf.setAutomaticRecoveryEnabled(true);
        cf.setRequestedHeartbeat(5);
        cf.setVirtualHost("boofa");

        final Set<String> buckets = new HashSet();
        for (int i=0; i<100; i++) {
            buckets.add("" + i);
        }

        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);

        final Connection c = cf.newConnection();

        for (int i=0; i<10; i++) {
            new Thread() {
                public void run() {
                    try {

                        final RabbitBucketDistributor s = new RabbitBucketDistributor(c, "mongoconsumers", buckets, scheduler,
                            1, TimeUnit.MINUTES, // announce period
                            2, TimeUnit.MINUTES, // expiration period
                            5, 5, TimeUnit.SECONDS // partition update delay/period
                        );

                        s.start();

                        Runtime.getRuntime().addShutdownHook(new Thread() {
                            public void run() {
                                try {
                                    s.stop();
                                    scheduler.shutdownNow();
                                    c.close();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        });

                        final MongoClient client = new MongoClient();
                        final DB brokerDb = client.getDB("broker");
                        final String queueName = "funqueue";

                        final DBCollection queue = brokerDb.getCollection(queueName);

                        class Buckets implements AutoCloseable {

                            final Set<String> buckets;

                            public Buckets(Set<String> buckets) {
                                this.buckets = buckets;
                            }

                            @Override
                            public void close() throws Exception {
                                s.release(buckets);
                            }
                        }

                        while (true) {

                            try (
                                Buckets b = new Buckets(s.buckets());
//                Cursor cursor = queue.find(start("processing").is(false).get())
                                Cursor cursor = queue.find(QueryBuilder.start("processing").is(false).and("bucket").in(b.buckets).get())
                                    .batchSize(1000);
                            ) {
                                int seq = 0;
                                final int max = 5000;
                                while (cursor.hasNext() && seq < max) {

                                    DBObject next = cursor.next();

                                    DBObject query = QueryBuilder.start("_id").is(next.get("_id")).get();
                                    DBObject update = QueryBuilder.start("processing").is(true).get();

                                    WriteResult result = queue.update(query, update, false, false, WriteConcern.ACKNOWLEDGED);
                                    if (result.getN() > 0) {

                                        // do something

                                        queue.remove(query, WriteConcern.UNACKNOWLEDGED); // we are finished

                                        inc();

                                        seq++;
                                    } else {
                                        // skip, we didn't lock it
                                    }
                                }
                            }

                            Thread.sleep(100);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }.start();
        }

    }
}

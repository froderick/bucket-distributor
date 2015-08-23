package org.funtastic.dist.rabbit;

import com.rabbitmq.client.Connection;

import java.util.concurrent.ScheduledExecutorService;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

/**
 * Defines the parameters for configuring and managing a rabbit bucket distributor
 * in a java-friendly way.
 */
public class RabbitJavaDist {

    private Connection conn;
    private String name;
    private Set<String> defaultBuckets;
    private ScheduledExecutorService scheduler;

    private Long peersPeriod;
    private TimeUnit peersUnits;

    private Long expirationPeriod;
    private TimeUnit expirationUnits;

    private Long partitionDelay;
    private Long partitionPeriod;
    private TimeUnit partitionUnits;

    public void setConnection(Connection conn) { this.conn = conn; }

    /**
     * This is used as a base prefix to name all the rabbitmq configuration related
     * to the bucket distributor. This is required.
     */
    public void setName(String name) { this.name = name; }

    /**
     * This is used to initialize the bucket consumer queue on startup if it is
     * not already initialized. This is required.
     */
    public void setDefaultBuckets(Set<String> defaultBuckets) { this.defaultBuckets = defaultBuckets; }

    /**
     * The scheduler thread pool used to perform periodic actions to maintain
     * the list of peers and the consumer partition size. This is required.
     */
    public void setScheduler(ScheduledExecutorService scheduler) { this.scheduler = scheduler; }

    /**
     * The period at which the distributor announces itself to its peers and
     * maintains its up-to-date set of known-healthy peers. Defaults to 1 minute.
     */
    public void setPeersPeriod(long period) { this.peersPeriod = period; }
    public void setPeersUnits(TimeUnit units) { this.peersUnits = units; }

    /**
     * If the distributor hasn't heard from a peer after this period, it considers
     * the peer to be gone and expires it from the set of known peers. Defaults to
     * 2 minutes.
     */
    public void setExpirationPeriod(long period) { this.expirationPeriod = period; }
    public void setExpirationUnits(TimeUnit units) { this.expirationUnits = units; }

    /**
     * The initial delay before the distributor begins to examine its known peers
     * to determine the bucket partition size it should use. Defaults to 5 seconds.
     */
    public void setPartitionDelay(long delay) { this.partitionDelay = delay; }

    /**
     * The period at which the distributor examines its known peers and updates its
     * bucket partition size to match. Defaults to 5 seconds.
     */
    public void setPartitionPeriod(long period) { this.partitionPeriod = period; }
    public void setPartitionUnits(TimeUnit units) { this.partitionUnits = units; }

    private static final String NS = "bucket-distributor-clj.core";
    private static final IFn start, stop, buckets, release;
    static {

        long a = System.currentTimeMillis();

        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read(NS));
        start = Clojure.var(NS, "start-bucket-distributor!");
        stop = Clojure.var(NS, "stop-bucket-distributor!");
        buckets = Clojure.var(NS, "acquire-buckets!");
        release = Clojure.var(NS, "release-buckets!");

        long end = System.currentTimeMillis();
        System.out.println("require: " + (end - a));
    }

    private Object dist;

    public void start() {
        dist = start.invoke(conn, name, defaultBuckets, scheduler, new java.util.HashMap());
    }

    public Set<String> buckets() {
        return (Set) buckets.invoke(dist);
    }

    public void release(Set<String> buckets) {
        release.invoke(dist, buckets);
    }

    public void stop() {
        stop.invoke(dist);
    }
}


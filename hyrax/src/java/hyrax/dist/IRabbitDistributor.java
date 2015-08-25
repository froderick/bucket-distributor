package hyrax.dist;

import com.rabbitmq.client.Connection;

import java.util.concurrent.ScheduledExecutorService;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Defines the parameters for configuring and managing a rabbit bucket distributor
 * in a java-friendly way.
 */
public interface IRabbitDistributor extends IDistributor {

    void setConnection(Connection conn);

    /**
     * This is used as a base prefix to name all the rabbitmq configuration related
     * to the bucket distributor. This is required.
     */
    void setName(String name);

    /**
     * This is used to initialize the bucket consumer queue on startup if it is
     * not already initialized. This is required.
     */
    void setDefaultBuckets(Set<String> defaultBuckets);

    /**
     * The scheduler thread pool used to perform periodic actions to maintain
     * the list of peers and the consumer partition size. This is required.
     */
    void setScheduler(ScheduledExecutorService scheduler);

    /**
     * The period at which the distributor announces itself to its peers and
     * maintains its up-to-date set of known-healthy peers. Defaults to 1 minute.
     */
    void setPeersPeriod(long period);
    void setPeersUnits(TimeUnit unit);

    /**
     * If the distributor hasn't heard from a peer after this period, it considers
     * the peer to be gone and expires it from the set of known peers. Defaults to
     * 2 minutes.
     */
    void setExpirationPeriod(long period);
    void setExpirationUnits(TimeUnit unit);

    /**
     * The initial delay before the distributor begins to examine its known peers
     * to determine the bucket partition size it should use. Defaults to 5 seconds.
     */
    void setPartitionDelay(long delay);

    /**
     * The period at which the distributor examines its known peers and updates its
     * bucket partition size to match. Defaults to 5 seconds.
     */
    void setPartitionPeriod(long period);
    void setPartitionUnits(TimeUnit unit);

    /**
     * Starts the distributor. After calling start, buckets may be available
     * for acquisition.
     */
    void start();

    /**
     * Stops the distributor. Note that any buckets that have been acquired but
     * not yet released will prevent the distributor's shutdown until the release
     * happens. In this case, the distributor will block until this condition is
     * met.
     */
    void stop();
}


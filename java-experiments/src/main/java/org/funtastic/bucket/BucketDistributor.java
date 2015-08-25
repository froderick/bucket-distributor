package org.funtastic.bucket;

import java.util.Set;

/**
 * A mechanism for coordinated distribution of hash buckets across a networked
 * cluster of computers.
 *
 * <p>Implementations of this interface should be thread safe.</p>
 *
 * <p>TODO: include a non-blocking api</p>
 *
 * @see RabbitBucketDistributor
 */
public interface BucketDistributor {

    /**
     * Returns the set of buckets currently available to a distributor
     * instance.
     *
     * <p>This method may return an empty set if no buckets are yet available.
     * It may block, depending on the implementation.</p>
     */
    Set<String> buckets();

    /**
     * Releases the current set of buckets back to the cluster.
     *
     * <p>This method is a way of indicating that the client is done with
     * the current set of buckets. The distributor is responsible
     * for fetching the next set of buckets. This method may block,
     * depending on the implementation.</p>
     */
    void release(Set<String> buckets);
}

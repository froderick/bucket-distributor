(ns hyrax.dist.api)

(defprotocol Distributor "A mechanism for coordinated distribution of
                                hash buckets."

  (acquire-buckets! [this] "Returns the set of buckets currently available to a
                   distributor instance. This function may return an empty set
                   if no buckets are yet available. Must not block.")

  (release-buckets! [this buckets] "Releases the current set of buckets back to the
                           cluster.  This method is a way of indicating that
                           the client is done with the current set of buckets.
                           The distributor is responsible for fetching the next
                           set of buckets. Must not block."))

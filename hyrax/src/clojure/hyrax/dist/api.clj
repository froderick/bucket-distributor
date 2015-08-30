(ns hyrax.dist.api
  "A mechanism for coordinated distribution of hash buckets.")

(defprotocol Distributor 
  (acquire-buckets* [this])
  (release-buckets* [this buckets]))

(defn acquire-buckets! 
  "Returns the set of buckets currently available to a
   distributor instance. This function may return an empty set
   if no buckets are yet available. Must not block."
  [this]
  (acquire-buckets* this))

(defn release-buckets!
  "Releases the current set of buckets back to the
   cluster.  This method is a way of indicating that
   the client is done with the current set of buckets.
   The distributor is responsible for fetching the next
   set of buckets. Must not block."
  [this buckets]
  (release-buckets* this buckets))


(ns hyrax.dist.api
  "A mechanism for coordinated distribution of hash buckets."
  (:require [schema.core :as s]))

(defprotocol Distributor 
  (acquire-buckets* [this] )
  (release-buckets* [this buckets] ))

(s/defn ^:always-validate acquire-buckets! :- #{String} 
  "Returns the set of buckets currently available to a
   distributor instance. This function may return an empty set
   if no buckets are yet available. Must not block."
  [this]
  (acquire-buckets* this))

(s/defn ^:always-validate release-buckets! :- nil 
  "Releases the current set of buckets back to the
   cluster.  This method is a way of indicating that
   the client is done with the current set of buckets.
   The distributor is responsible for fetching the next
   set of buckets. Must not block."
  [this buckets :- #{String}]
  (release-buckets* this buckets))


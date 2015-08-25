(ns hyrax.dist.rabbit-interop
  (:require [hyrax.dist.api :as api]
            [hyrax.dist.rabbit :as rabbit])
  (:gen-class
    :name hyrax.dist.RabbitDistributor
    :implements [hyrax.dist.IDistributor hyrax.dist.IRabbitDistributor]
    :main false
    :init init
    :state state))

(defn -init []
  [[] (atom {})])

(defn state [this]
  (.state this))

(defn setfield
  [this key value]
      (swap! (state this) into {key value}))

(defn -setConnection [this v]
  (setfield this :conn v))

(defn -setName [this v]
  (setfield this :name v))

(defn -setDefaultBuckets [this  v]
  (setfield this :default-buckets v))

(defn -setScheduler [this  v]
  (setfield this :scheduler v))

(defn -setPeersPeriod [this  v]
  (setfield this :peers-period v))

(defn -setPeersUnits [this v]
  (setfield this :peers-units v))

(defn -setExpirationPeriod [this  v]
  (setfield this :expiration-period v))

(defn -setExpirationUnits [this  v]
  (setfield this :expiration-units v))

(defn -setPartitionDelay [this  v]
  (setfield this :partition-delay v))

(defn -setPartitionPeriod [this  v]
  (setfield this :partition-period v))

(defn -setPartitionUnits [this  v]
  (setfield this :partition-units v))

(defn -start [this]
  (let [{:keys [conn name default-buckets scheduler]} @(state this)]
    (setfield this :dist (rabbit/start-bucket-distributor! conn name default-buckets scheduler {}))))

(defn dist [this]
  (let [{:keys [dist]} @(state this)]
    dist))

(defn -stop [this]
  (rabbit/stop-bucket-distributor! (dist this)))

(defn -buckets [this]
  (api/acquire-buckets! (dist this)))

(defn -release [this buckets]
  (api/release-buckets! (dist this) buckets))


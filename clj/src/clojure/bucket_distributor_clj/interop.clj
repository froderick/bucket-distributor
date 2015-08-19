(ns bucket-distributor-clj.interop
  (:require [bucket-distributor-clj.core :as core])
  (:gen-class
    ;:name org.funtastic.dist.rabbit.RabbitDistributor
    ;:load-impl-ns org.funtastic.dist.rabbit
    :implements [org.funtastic.dist.Distributor org.funtastic.dist.rabbit.Service]
    :main false
    :init init
    :state state))

(defn -init []
  [[] (atom {})])

(defn setfield
  [^bucket_distributor_clj.interop this key value]
      (swap! (.state this) into {key value}))

(defn -setConnection [this  conn]
  (setfield this :conn conn))

(defn -setName [this  conn]
  (setfield this :conn conn))

(defn -setDefaultBuckets [this  conn]
  (setfield this :conn conn))

(defn -setScheduler [this  conn]
  (setfield this :conn conn))

(defn -setScheduler [this  conn]
  (setfield this :conn conn))

(defn -setPeersPeriod [this  conn]
  (setfield this :conn conn))

(defn -setPeersUnits [this conn]
  (setfield this :conn conn))

(defn -setExpirationPeriod [this  conn]
  (setfield this :conn conn))

(defn -setExpirationUnits [this  conn]
  (setfield this :conn conn))

(defn -setPartitionDelay [this  conn]
  (setfield this :conn conn))

(defn -setPartitionPeriod [this  conn]
  (setfield this :conn conn))

(defn -setPartitionUnits [this  conn]
  (setfield this :conn conn))

(defn -start [])

(defn -stop [])


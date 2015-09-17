(ns hyrax.membership-interop
  (:require [hyrax.membership :as m])
  (:gen-class
    :name hyrax.RabbitMembershipGroup
    :implements [hyrax.IMembershipGroup hyrax.IRabbitMembershipGroup]
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

(defn -join [this]
  (let [{:keys [conn name default-buckets scheduler]} @(state this)]
    (setfield this :dist (m/join! conn name default-buckets scheduler {}))))

(defn dist [this]
  (let [{:keys [dist]} @(state this)]
    dist))

(defn -leave [this]
  (m/leave! (dist this)))

(defn -members [this]
  (m/members (dist this)))

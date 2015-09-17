(ns hyrax.membership-interop

  "next up is to create a mechanism for scheduling an event to fire
  probabilistically based on the number of peers available, n times per period.

  (fire group scheduler start frequency units f)

  actually, just make an implementation of runnable that supports
  probabilistically invoking its parameter based on the current
  membership size in the group.


  (let [fire? #(maybe-fire (members group) concurrent-fires)
        fut (.schedule scheduler x y z #(when fire? (do-it)))]
    fut)
  "

  (:require [hyrax.membership :as m])
  (:gen-class
    :name hyrax.RabbitMembershipGroup
    :implements [hyrax.IMembershipGroup hyrax.IRabbitMembershipGroup]
    :main false
    :init init
    :state state))


(defn maybe-fire [members concurrency]
  (let [buckets  (int (/ members concurrency))
        rand (-> (java.util.Random.) (.nextInt buckets))]
    (zero? rand)))

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
  (let [{:keys [conn name scheduler]} @(state this)]
    (setfield this :dist (m/join! conn name scheduler {}))))

(defn dist [this]
  (let [{:keys [dist]} @(state this)]
    dist))

(defn -leave [this]
  (m/leave! (dist this)))

(defn -members [this]
  (m/members (dist this)))

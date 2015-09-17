(ns hyrax.membership-test
  (:use midje.sweet)
  (:require [hyrax.dist.rabbit :refer :all]
            [hyrax.dist.api :as api]
            [hyrax.membership :as m])
  (:require [langohr.core      :as rmq]
            [langohr.channel   :as lch]
            [langohr.queue     :as lq]
            [langohr.exchange  :as le]
            [langohr.consumers :as lc]
            [langohr.basic     :as lb])
  (:import [java.util.concurrent BlockingQueue LinkedBlockingQueue TimeUnit Executors 
            ScheduledExecutorService Future]
           [com.rabbitmq.client Connection]))

(def ^:private rabbit-info {:vhost "boofa"
                            :requested-heartbeat 1 
                            :connection-timeout 5000})

(fact "basic usage"
  (with-conn [conn rabbit-info]
    (let [exchange-name "bucket-exchange.broadcast"
          
          _ (with-chan [ch conn] ;; clear exchange
              (try (le/delete ch exchange-name)
                   (catch Exception e)))
  
          make-appender (fn [vec-atom] ;; start consumers
                          (fn [peer-id message]
                            (swap! vec-atom #(conj % [peer-id message]))))
          
          group-name "bucket-exchange"
          scheduler (Executors/newScheduledThreadPool 1)

          handler-fn-atom (atom [])
          handler-fn (fn [a b] (reset! handler-fn-atom [a b]))
          options {:handler-fn handler-fn}]
  
      (with-open [a (m/join! conn group-name scheduler options)
                  b (m/join! conn group-name scheduler options)]

        (Thread/sleep 200)

        (let [count-a (count (m/members a))
              count-b (count (m/members b))
              [before after] @handler-fn-atom]

        [count-a count-b (count before) (count after)]))))

  => [2 2   ; both nodes should have the same member list
      1 2]) ; the handle-fn should only be called if the member list has changed



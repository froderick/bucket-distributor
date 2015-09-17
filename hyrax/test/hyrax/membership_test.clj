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
          options {}
  
          [a b] (with-open [a (m/join! conn group-name scheduler options)
                            b (m/join! conn group-name scheduler options)]
                  (Thread/sleep 200)
                  [(m/members a) (m/members b)])]

      [(count a) (count b)])) => [2 2])



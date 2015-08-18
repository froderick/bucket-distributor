(ns bucket-distributor-clj.core-test
  (:use midje.sweet)
  (:require [bucket-distributor-clj.core :refer :all])
  (:require [langohr.core      :as rmq]
            [langohr.channel   :as lch]
            [langohr.queue     :as lq]
            [langohr.exchange  :as le]
            [langohr.consumers :as lc]
            [langohr.basic     :as lb]))

(fact "bucket consumers provide concurrent exclusion of buckets"
   (with-conn [conn {:vhost "boofa"}]
    (let [queue-name "bucket-queue"
          prefetch 2]

      ;; setup bucket queue
      (with-chan [ch conn]
        (try (lq/delete ch queue-name)
             (catch Exception e))
        (lq/declare ch queue-name {:durable false :exclusive false :auto-delete false})
        (doseq [i (range 4)]
          (lb/publish ch "" queue-name (str i))))

      ;; start consumers
      (let [a (start-bucket-consumer! conn queue-name prefetch "a")
            b (start-bucket-consumer! conn queue-name prefetch "b")
            buckets-wait! #(loop []
                             (let [buckets (buckets! %)]
                               (if (< (count buckets) prefetch)
                                 (do 
                                   (Thread/sleep 100)
                                   (recur))
                                 buckets)))]
        ;; acquire buckets from multiple consumers to verify
        ;; that buckets are exclusively allocated until released.
        (try
          (let [b1 (buckets-wait! a)
                b2 (buckets-wait! b)
                _ (release! a b1)
                _ (release! b b2)
                b3 (buckets-wait! a)
                _ (release! a b3)]
            [b1 b2 b3])
          (finally
            (try 
              ;; clean up with :force-stop in case the test borks
              (stop-bucket-consumer! a :force-stop true)
              (stop-bucket-consumer! b :force-stop true)
              (catch Exception e
                (.printStackTrace e)))))))) 

   ;; buckets are handed out in sequence
   => [["0" "1"] ["2" "3"] ["0" "1"]])

(fact "bucket consumers block on shutdown (by default) until the client
       has released all acquired buckets"
   (with-conn [conn {:vhost "boofa"}]
    (let [queue-name "bucket-queue"
          prefetch 2]

      ;; setup bucket queue
      (with-chan [ch conn]
        (try (lq/delete ch queue-name)
             (catch Exception e))
        (lq/declare ch queue-name {:durable false :exclusive false :auto-delete false})
        (doseq [i (range 4)]
          (lb/publish ch "" queue-name (str i))))

      ;; start consumers
      (let [a (start-bucket-consumer! conn queue-name prefetch "a")
            buckets-wait! #(loop []
                             (let [buckets (buckets! %)]
                               (if (< (count buckets) prefetch)
                                 (do 
                                   (Thread/sleep 100)
                                   (recur))
                                 buckets)))]
        (try
          (let [b1 (buckets-wait! a)
                shutdown-future (future (stop-bucket-consumer! a))
                shutdown-attempt (deref shutdown-future 50 :timeout)
                _ (release! a b1)
                shutdown-attempt2 (deref shutdown-future 50 :timeout)]
            [shutdown-attempt shutdown-attempt2])
          (finally
            (try 
              ;; clean up with :force-stop in case the test borks
              (stop-bucket-consumer! a :force-stop true)
              (catch Exception e
                (.printStackTrace e)))))))) 

   ;; initial shutdown blocks until release! is performed
   => [:timeout nil])

(fact "broadcast consumers receive all broadcasted events for a
       given exchange"
   (with-conn [conn {:vhost "boofa"}]
    (let [exchange-name "bucket-exchange"]

      ;; clear exchange
      (with-chan [ch conn]
        (try (le/delete ch exchange-name)
             (catch Exception e)))

      ;; start consumers
      (let [make-appender (fn [vec-atom]
                            (fn [peer-id message]
                              (swap! vec-atom #(conj % [peer-id message]))))

            a-messages (atom [])
            a (start-broadcast-consumer! conn exchange-name (make-appender a-messages))

            b-messages (atom [])
            b (start-broadcast-consumer! conn exchange-name (make-appender b-messages))

            receive-wait (fn [cond?]
                           (loop []
                             (when-not (cond?)
                               (do 
                                 (Thread/sleep 100)
                                 (recur)))))

            wait-size (fn [messages-atom size]
                            (let [f (future
                                      (receive-wait #(>= (count @messages-atom) size)))]
                              (deref f 100 :timeout)))]
        (try

          (send-broadcast! conn exchange-name "foo" "bar")
          (send-broadcast! conn exchange-name "baz" "bing")

          (wait-size a-messages 2)
          (wait-size b-messages 2)

          [@a-messages @b-messages]
          (finally
            (try 
              (stop-broadcast-consumer! a)
              (stop-broadcast-consumer! b)
              (catch Exception e
                (.printStackTrace e)))))))) 

   ;; initial shutdown blocks until release! is performed
   => [[[nil "bar"] [nil "bing"]] [[nil "bar"] [nil "bing"]]])



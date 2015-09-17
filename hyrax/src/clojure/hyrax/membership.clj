(ns hyrax.membership

  "Enables group membership computation via rabbit broadcasts."

  (:require [clojure.set]
            [clojure.tools.logging :as log]
            [langohr.core      :as rmq]
            [langohr.channel   :as lch]
            [langohr.queue     :as lq]
            [langohr.exchange  :as le]
            [langohr.consumers :as lc]
            [langohr.basic     :as lb]
            [hyrax.rabbit :refer [require-ch!
                                  with-chan
                                  with-conn
                                  queue-exists?]])
  (:import [java.util.concurrent BlockingQueue LinkedBlockingQueue TimeUnit Executors 
            ScheduledExecutorService Future]))

(def ^:private peer-id-header "peer-id")

(let [names (->> (clojure.java.io/resource "names.txt")
                 slurp 
                 clojure.string/split-lines)]
  (defn peer-id []
    (let [hostname (-> (java.net.InetAddress/getLocalHost) .getHostName)
          id (-> names
                 shuffle
                 first)]
      (str hostname "/" id))))

(defn send! [conn exchange-name peer-id ^String message]
  (with-chan [ch conn]
    (lb/publish ch exchange-name "" (.getBytes message)
                {:headers {peer-id-header peer-id}})))

(defrecord BroadcastConsumer [ch consumer-tag])

(defn- start-consumer! 
  "Creates and starts a broadcast consumer, returns a record that contains
   the state of the consumer. The handler function signature looks like
   the following: (fn [peer-id message])."
  [conn exchange-name handler-fn]

  (let [ch (require-ch! conn)
        queue-name (-> ch lq/declare :queue)
        handler (fn [ch {:keys [delivery-tag headers]} ^bytes payload]
                  (let [; rabbit driver weirdness
                        ^com.rabbitmq.client.LongString sender-wrapper (get headers peer-id-header)
                        sender-id (-> sender-wrapper
                                      (.getBytes)
                                      (String.))
                        broadcast (String. payload)]
                    (try 
                      (handler-fn sender-id broadcast)
                      (catch Exception e
                        (.printStackTrace e))
                      (finally 
                        (lb/ack ch delivery-tag)))))]

    (lb/qos ch 10)
    (le/declare ch exchange-name "fanout")
    (lq/bind ch queue-name exchange-name)

    (map->BroadcastConsumer {:ch ch
                             :consumer-tag (lc/subscribe ch queue-name handler)})))

(defn- stop-consumer! 
  "Shuts down the broadcast consumer. Returns nil."
  [{:keys [ch consumer-tag]}]

  (println "###" ch consumer-tag)

  (lb/cancel ch consumer-tag)
  (lch/close ch)
  nil)

(defn- announce-swap
  [peer-id {:keys [peers] :as state}]

  (let [now (System/currentTimeMillis)
        updated-peers (assoc peers peer-id now)]
    (assoc state :peers updated-peers)))

(defn- retract-swap
  [peer-id {:keys [peers] :as state}]
  (assoc state :peers (dissoc peers peer-id)))

(defn- handle-broadcast! 
  [peer-id state-atom broadcast! sender-id ^String msg]

  (when-not (= peer-id sender-id)
    (log/debugf "[%s] received [%s]" peer-id msg))

  (cond
    (.startsWith msg "announce:")
    (let [peer-id (-> msg (clojure.string/split #":") second)]
      (swap! state-atom #(announce-swap peer-id %)))

    (.startsWith msg "retract:")
    (let [peer-id (-> msg (clojure.string/split #":") second)]
      (swap! state-atom #(retract-swap peer-id %)))

    (.startsWith msg "poll")
    (broadcast! (str "announce:" peer-id))))

;; handle periodic self-announce and peer expiration

(defn- expire-swap [expiration-period ^TimeUnit expiration-units peer-id {:keys [peers] :as state}]
  (let [now (System/currentTimeMillis)
        oldest-permitted (- now (.toMillis expiration-units expiration-period))
        expired (->> peers
                     (filter #(< (second %) oldest-permitted))
                     (into #{}))]

    (doseq [[id _] expired]
      (log/debugf "[%s] peer-expired: %s" peer-id id))

    (assoc state :peers (->> (clojure.set/difference peers expired)
                             (into {})))))

(defn- update-peers! 
  [broadcast! peer-id state-atom {:keys [expiration-period expiration-units]}]
  (try
    (broadcast! (str "announce:" peer-id))
    (swap! state-atom #(expire-swap expiration-period expiration-units peer-id %))
    (catch Exception e
      (.printStackTrace e))))

;; handle recalculating the partition size and changing the qos on the
;; bucket consumer to match

(defn- log-peer-changes 
  [last-peers peers peer-id]
  (let [make-set #(->> % (map first) (into #{}))
        a (make-set last-peers)
        b (make-set peers)]
    (doseq [id (clojure.set/difference b a)]
      (log/debugf "[%s] peer added: %s%s" peer-id id (if (= id peer-id) " (self)" "")))
    (doseq [id (clojure.set/difference a b)]
      (log/debugf "[%s] peer removed: %s%s" peer-id id (if (= id peer-id) " (self)" "")))))

(defn- peer-listener!
  "Logs out when peers join and leave the group"
  [group-name peer-id {last-peers :peers} {:keys [peers]}]

  (when (log/enabled? :debug)
    (log-peer-changes last-peers peers peer-id)))

(declare leave!)

;; core stuff that gets passed around
(defrecord MembershipGroup [options peer-id state-atom 
                            broadcast! broadcast-consumer peers-future]
  java.io.Closeable
  (close [this]
    (leave! this)))

(defn join!
  "Returns a MembershipGroup"
  [conn group-name ^ScheduledExecutorService scheduler options]

  (let [defaults {:peers-period      1 :peers-units      TimeUnit/MINUTES
                  :expiration-period 2 :expiration-units TimeUnit/MINUTES}
        options (merge defaults options)
        exchange (str group-name ".broadcast")
        peer-id (peer-id)

        _ (log/infof "[%s] starting membership group" peer-id)

        state-atom (-> (atom {:peers {} ; atomic peer/bucket partition size state
                              :shutdown false})
                       (add-watch :watch
                                  #(peer-listener! group-name peer-id %3 %4)))

        broadcast! #(send! conn exchange peer-id %)

        consumer (start-consumer! conn exchange
                                  #(handle-broadcast! peer-id state-atom broadcast! %1 %2))

        _ (broadcast! "poll")

        {:keys [peers-period peers-units]} options

        peers-future (.scheduleAtFixedRate scheduler #(update-peers! broadcast! peer-id state-atom options)
                                           0 peers-period peers-units)]

    (map->MembershipGroup {:options options
                           :peer-id peer-id
                           :state-atom state-atom
                           :broadcast! broadcast!
                           :consumer consumer
                           :peers-future peers-future})))

(defn leave! 
  "Takes a MembershipGroup and shuts it down, effectively leaving the group"
  [{:keys [consumer broadcast! ^Future peers-future peer-id state-atom]}]

  (log/infof "[%s] leaving group" peer-id)

  (.cancel peers-future true)
  (stop-consumer! consumer)
  (broadcast! (str "retract:" peer-id)))

(defn members
  "Returns a set of the current group member peer-ids."
  [{:keys [state-atom]}]
  (->> @state-atom
       :peers
       (map first)
       (into #{})))


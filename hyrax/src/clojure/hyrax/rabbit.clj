(ns hyrax.rabbit

  "Contains utility functions that are useful when working with the
  rabbit driver. They make use of the langhor api."

  (:require [clojure.tools.logging :as log]
            [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.queue :as lq]))

(defn require-ch! 

  "Opens a channel on the supplied rabbit connection and returns it.
  This is just a helper function, as the builtin open method in the
  rabbit driver may return null. This method throws an exception instead
  of returning null."

  [conn]
  (let [ch (lch/open conn)]
    (when-not ch
      (throw (Exception. "cannot open channel")))
    ch))

(defmacro with-conn
  "Evaluates body while providing a connection to the requested rabbit broker.
  The binding provides the broker information for the connection and the name to
  which that is bound for evaluation of the body.

  (with-conn [conn params-map] ...)"

  [binding & body]
  `(let [config# ~(second binding)
         ~(first binding) (rmq/connect config#)]
     (try
       ~@body
       (finally 
         (try
           (rmq/close ~(first binding))
           (catch Exception e#
             (.printStackTrace e#)))))))

(defmacro with-chan

  "Evaluates body while providing a channel to the requested rabbit connection.
  The binding provides the connection and the name to which the channel is bound
  for evaluation of the body.

  (with-chan [ch conn] ...)"

  [binding & body]
  `(let [conn# ~(second binding)
         ~(first binding) (require-ch! conn#)]
     (try
       ~@body
       (finally 
         (try
           (rmq/close ~(first binding))
           (catch Exception e#))))))

(defn with-rabbit-lock!

  "A mutex implemented with rabbit's exclusive queues. The lock is held for the
  duration of invoking f. Only a single connected client may declare a given
  queue as exclusive, so while slow this is a convenient way of doing
  distributed locking."

  [conn queue-name instance-id f]
  (with-chan [ch conn]
    (let [acquired (try
                     (lq/declare ch queue-name {:durable false
                                                :exclusive true
                                                :auto-delete false})
                     true
                     (catch Exception e
                       false))]
      (when acquired
        (log/debugf "[%s] acquired owner: %s" instance-id queue-name)
        (try 
          (f)
          (finally 
            (lq/delete ch queue-name)
            (log/debugf "[%s] released owner: %s" instance-id queue-name))))

      acquired)))

(defn queue-exists?
  [conn queue-name]
  (with-chan [ch conn]
    (try 
      (lq/declare-passive ch queue-name)
      true
      (catch Exception e
        false))))

(ns redis
  (:require [mount.core :refer [defstate]]
            [taoensso.carmine :as car]
            [config]))

(defstate conn
  :start
  {:pool (car/connection-pool {})
   :spec {:uri (config/redis-uri (config/fetch))}}
  :stop
  (when-let [pool (:pool conn)]
    (.close pool)))

(defmacro wconn* [& body]
  `(car/wcar conn ~@body))

(defmacro with-txn [& body]
  `(wconn*
    (car/multi)
    ~@body
    (car/exec)))

(defn kexpire [key seconds]
  (car/expire key (or seconds (* 60 60))))

(defn hset [key coll]
  (apply (partial car/hset key) (mapcat identity coll)))

(defn hgetall [key]
  (car/hgetall key))

(defn lrange
  ([key]
   (lrange key 0 -1))
  ([key start end]
   (car/lrange key start end)))

(defn linsert
  "
  Push a single el or collection of elements to a redis list.
  Maintains a list size of n with older elements being pushed out (default 20).
  If el is nil, the list is removed."
  ([key el]
   (linsert key el 19))
  ([key el n]
   (if (nil? el)
     (car/del key)
     (do
       (if (sequential? el)
         (doseq [item el]
           (car/rpush key item))
         (car/rpush key el))
       (car/ltrim key 0 (- n 1))))))

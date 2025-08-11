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

(defn expr [key seconds]
  (car/expire key (or seconds (* 60 60))))

(defn hset [key coll]
  (apply (partial car/hset key) (mapcat identity coll)))

(defn hgetall [key]
  (car/hgetall key))

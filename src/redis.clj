(ns redis
  (:require [mount.core :refer [defstate]]
            [taoensso.carmine :as car]
            [config]))

(defmacro wcar* [& body]
  `(car/wcar {:pool {} :spec {:uri (config/redis-uri (config/fetch))}}
     ~@body))

(defstate conn
  :start {:pool {} :spec {:uri (config/redis-uri (config/fetch))}}
  :stop nil) ; Carmine manages the pool lifecycle, no explicit stop needed for basic usage

(defmacro with-transaction [& body]
  `(wcar*
    (car/multi)
    ~@body
    (car/exec)))

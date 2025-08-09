(ns redis
  (:require [mount.core :refer [defstate]]
            [taoensso.carmine :as car]
            [taoensso.carmine.connections :as car-conn]
            [config]))

(defstate conn
  :start (let [spec (car-conn/make-conn-spec
                     :uri (config/redis-uri (config/fetch)))]
           (car-conn/make-conn-pool spec))
  :stop (car/conn-pool-shutdown conn))

(defmacro wcar* [& body]
  `(car/wcar conn ~@body))

(defmacro with-transaction [& body]
  `(wcar*
    (car/multi)
    ~@body
    (car/exec)))

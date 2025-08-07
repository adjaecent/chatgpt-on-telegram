(ns config
  (:require [aero.core :as aero]
            [clojure.java.io :as io]))

(defn fetch
  ([] (fetch (or (System/getenv "APP_ENV") "dev")))
  ([profile]
   (aero/read-config (io/resource "config.edn")
                     {:profile (keyword profile)})))

(defn openai-key [config]
  (get-in config [:secrets :openai-key]))

(defn telegram-bot-key [config]
  (get-in config [:secrets :telegram-bot-key]))

(defn datomic-uri [config]
  (get-in config [:secrets :datomic-uri]))

(defn session-ttl-minutes [config]
  (:session-ttl-minutes config))

(ns session-sweeper
  (:require [datomic :as d]
            [datomic.api :as da]
            [config]
            [mount.core :refer [defstate]]
            [clojure.tools.logging :as log]))

(defn- get-old-session-ids [db ttl-minutes]
  (let [cutoff (java.util.Date. (- (.getTime (java.util.Date.)) (* ttl-minutes 60 1000)))]
    (da/q '[:find [?e ...]
            :in $ ?cutoff
            :where
            [?e :session/last-updated ?t]
            [(< ?t ?cutoff)]]
          db cutoff)))

(defn- sweep-sessions [conn ttl-minutes]
  (let [db (da/db conn)
        old-session-ids (get-old-session-ids db ttl-minutes)]
    (when (seq old-session-ids)
      (log/info "Sweeping old sessions:" old-session-ids)
      (let [message-ids (da/q '[:find [?m ...]
                                :in $ [?s ...]
                                :where [?s :session/messages ?m]]
                              db old-session-ids)]
        @(da/transact conn (mapv (fn [id] [:db.fn/retractEntity id]) (concat old-session-ids message-ids)))))))

(defn- sweeper-thread-fn [conn ttl-minutes]
  (fn []
    (while (not (.isInterrupted (Thread/currentThread)))
      (try
        (sweep-sessions conn ttl-minutes)
        (catch Throwable t
          (log/error t "Error while sweeping sessions")))
      (Thread/sleep (* 1000 60 5))))) ; run every 5 minutes

(defstate sweeper
  :start (let [ttl (config/session-ttl-minutes (config/fetch))
               thread (Thread. (sweeper-thread-fn d/conn ttl))]
           (.start thread)
           thread)
  :stop (.interrupt sweeper))

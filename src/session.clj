(ns session
  (:require [redis :as r]
            [sundry :refer [safe-parse-int safe-parse-long]]))

(defn- skey [id] (str "session:" id))
(defn- smkey [id] (str (skey id) ":messages"))
(def messages-ttl-seconds (* 60 60 2))
(def messages-to-keep 20)
(def schema
  {:chat-id                     [nil safe-parse-long]
   :current-user-message-id     [nil safe-parse-int]
   :current-response-message-id [nil safe-parse-int]
   :current-model-stack         [:fast keyword]})
(defn- fetch-schema [n] (into {} (for [[k v] schema] [k (nth v n)])))
(def schema-defaults (fetch-schema 0))
(def schema-transforms (fetch-schema 1))
(defn- transform-session [raw-session]
  (into
     {}
     (for [[k v] raw-session]
       (let [key (keyword k)
             t-fn (or (get schema-transforms key) identity)]
         [key (t-fn v)]))))

(defn write
  "
  Update data for an existing session. Optionally update messages.
  When no session data is provided, a default blank session is written out."
  ([id]
   (write id schema-defaults nil))
  ([id session]
   (r/wconn* (r/hset (skey id) session)))
  ([id session messages]
   (r/with-txn
     (r/hset (skey id) session)
     (r/linsert (smkey id) messages messages-to-keep)
     (r/kexpire (smkey id) messages-ttl-seconds))))

(defn fetch
  "
  To fetch the base session, just pass the id.
  To fetch just the session messages, additionally set messages-only? to true"
  ([id messages-only?]
   (if messages-only?
     (r/wconn* (r/lrange (smkey id)))
     (fetch id)))
  ([id]
   (let [session-data (r/wconn* (r/hgetall (skey id)))
         session (or (seq (partition 2 session-data)) schema-defaults)]
     (transform-session session))))

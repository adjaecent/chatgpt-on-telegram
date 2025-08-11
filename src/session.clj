;; TODO: Use a combination of LPUSH and LTRIM for the messages list and only keep a certain number of messages in the session
;; Store the session messages in separate list, like sesssion:123:messages
;; No need to expire the sesssion really, just pop messages after a certain number / tokens hits
(ns session
  (:require [redis :as r]
            [sundry :refer [safe-parse-long safe-parse-int]]))

(defn- skey [id] (str "session:" id))
(def ttl-seconds (* 60 60 2))
(def schema
  {:chat-id                     [nil safe-parse-long]
   :current-user-message-id     [nil safe-parse-int]
   :current-response-message-id [nil safe-parse-int]
   :messages                    [[] nil]
   :current-model-stack         [:fast keyword]})
(defn- fetch-schema [n] (into {} (for [[k v] schema] [k (nth v n)])))
(def schema-defaults (fetch-schema 0))
(def schema-transforms (fetch-schema 1))

(defn write [id data]
  (let [key (skey id)]
    (r/with-txn
      (r/hset key data)
      (r/expr key ttl-seconds))))

(defn write-new [id]
  (write id schema-defaults))

(defn fetch [id]
  (let [raw-result (r/wconn* (r/hgetall (skey id)))
        raw-session (or (seq (partition 2 raw-result)) schema-defaults)]
    (into
     {}
     (for [[k v] raw-session]
       (let [key (keyword k)
             t-fn (or (get schema-transforms key) identity)]
         [key (t-fn v)])))))

(ns session
  (:require [redis :as r]
            [taoensso.carmine :as car]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def session-ttl-seconds (* 60 60)) ; 1 hour

(defn- session-key [id]
  (str "session:" id))

(defn write [id data]
  (let [key (session-key id)
        messages-json (json/generate-string (:messages data))
        session-map (-> data
                        (assoc :messages messages-json)
                        (update :current-model-stack name))]
    (r/with-transaction
     (car/hmset key (mapcat identity session-map))
     (car/expire key session-ttl-seconds))))

(defn new []
  {:chat-id                     nil
   :current-user-message-id     nil
   :current-response-message-id nil
   :messages                    []
   :current-model-stack         :fast})

(defn- parse-long [s]
  (try (Long/parseLong s) (catch Exception _ nil)))

(defn fetch [id]
  (let [key (session-key id)
        raw-session (r/wcar* (car/hgetall key))]
    (when (seq raw-session)
      (let [session-map (into {} (for [[k v] (partition 2 raw-session)]
                                   [(keyword k) v]))]
        (-> session-map
            (update :messages #(json/parse-string % true))
            (update :current-user-message-id parse-long)
            (update :current-response-message-id parse-long)
            (update :chat-id parse-long)
            (update :current-model-stack keyword))))))

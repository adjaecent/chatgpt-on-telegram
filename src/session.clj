(ns session
  (:require [datomic :as d]
            [datomic.api :as da]
            [clojure.set :as set]))

(defn- session-to-datomic [id data]
  (let [message-tempid-map (mapv (fn [msg]
                                   (assoc msg :db/id (da/tempid :db.part/user)))
                                 (:messages data))
        session-data (-> data
                         (dissoc :messages)
                         (set/rename-keys {:chat-id :session/chat-id
                                           :current-user-message-id :session/current-user-message-id
                                           :current-response-message-id :session/current-response-message-id
                                           :current-model-stack :session/current-model-stack})
                         (assoc :db/id [:session/chat-id id])
                         (assoc :session/last-updated (java.util.Date.))
                         (assoc :session/messages (mapv :db/id message-tempid-map)))
        message-data (mapv (fn [msg]
                             (set/rename-keys msg {:role :message/role
                                                   :content :message/content}))
                           message-tempid-map)]
    (concat message-data [session-data])))

(defn write [id data]
  @(da/transact d/conn (session-to-datomic id data)))

(defn new []
  {:chat-id                     nil
   :current-user-message-id     nil
   :current-response-message-id nil
   :messages                    []
   :current-model-stack         :fast})

(defn- datomic-to-session [session-map]
  (when session-map
    (-> session-map
        (dissoc :db/id)
        (set/rename-keys {:session/chat-id :chat-id
                          :session/current-user-message-id :current-user-message-id
                          :session/current-response-message-id :current-response-message-id
                          :session/current-model-stack :current-model-stack
                          :session/messages :messages
                          :session/last-updated :last-updated})
        (update :messages (fn [msgs]
                            (mapv (fn [msg]
                                    (-> msg
                                        (dissoc :db/id)
                                        (set/rename-keys {:message/role :role
                                                          :message/content :content})))
                                  msgs))))))

(defn fetch [id]
  (let [db (da/db d/conn)]
    (->> (da/pull db
                  '[:db/id
                    :session/chat-id
                    :session/current-user-message-id
                    :session/current-response-message-id
                    {:session/messages [:db/id :message/role :message/content]}
                    :session/current-model-stack
                    :session/last-updated]
                  [:session/chat-id id])
         datomic-to-session)))

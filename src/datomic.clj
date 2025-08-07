(ns datomic
  (:require [config]
            [datomic.api :as d]
            [mount.core :refer [defstate]]))

(def session-schema
  [{:db/ident       :session/chat-id
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "The chat ID from Telegram"}
   {:db/ident       :session/current-user-message-id
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "The message ID of the user's last message"}
   {:db/ident       :session/current-response-message-id
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "The message ID of the bot's last response"}
   {:db/ident       :session/messages
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "A list of messages in the session"}
   {:db/ident       :session/current-model-stack
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "The current model stack being used"}
   {:db/ident       :session/last-updated
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "The timestamp of the last update"}])

(def message-schema
  [{:db/ident       :message/role
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "The role of the message sender (e.g., :user, :assistant)"}
   {:db/ident       :message/content
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The content of the message"}])

(def all-schemas (concat session-schema message-schema))

(defn- create-and-connect [uri]
  (d/create-database uri)
  (let [conn (d/connect uri)]
    @(d/transact conn all-schemas)
    conn))

(defstate conn
  :start (let [uri (config/datomic-uri (config/fetch))]
           (when-not (d/database-exists? uri)
             (create-and-connect uri))
           (d/connect uri))
  :stop (d/release conn))

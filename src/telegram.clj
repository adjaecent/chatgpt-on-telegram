(ns telegram
  (:require [mount.core :as mount :refer [defstate]]
            [config :as c]
            [threads :as t]
            [sundry :refer [when-let*]])
  (:import
    [org.telegram.telegrambots.client.jetty JettyTelegramClient]
    [org.eclipse.jetty.client HttpClient]
    [org.eclipse.jetty.client.transport HttpClientTransportOverHTTP]
    [org.telegram.telegrambots.meta.api.objects.message Message]
    [org.telegram.telegrambots.meta.exceptions TelegramApiException]
    [org.telegram.telegrambots.meta.api.methods.send SendMessage]
    [org.telegram.telegrambots.meta.api.methods.updatingmessages EditMessageText]
    [org.telegram.telegrambots.meta.api.objects Update]
    [org.telegram.telegrambots.meta.api.methods ActionType]
    [org.telegram.telegrambots.meta.api.methods.send SendChatAction]
    [org.telegram.telegrambots.longpolling.util LongPollingSingleThreadUpdateConsumer]
    [org.telegram.telegrambots.longpolling TelegramBotsLongPollingApplication]))

(defonce max-requests 100)
(defonce max-connections 5)
(defonce connect-timeout 5000)
(def bot-token (-> (c/fetch) (c/telegram-bot-key)))

(defn- start-http-client []
  (println "Starting Telegram HTTP client...")
  (-> (HttpClient. (HttpClientTransportOverHTTP. 1))
      (doto
        (.setMaxConnectionsPerDestination max-connections)
        (.setMaxRequestsQueuedPerDestination max-requests)
        (.setConnectTimeout connect-timeout)
        (.setExecutor (t/jetty-vthread-pool))
        (.start))
      (JettyTelegramClient. bot-token)))

(defn- create-bot [input-processor-fn]
  (reify LongPollingSingleThreadUpdateConsumer
    (^void consume [^LongPollingSingleThreadUpdateConsumer _ ^Update msg-update]
     (when-let* [_            (.hasMessage msg-update)
                 msg          (.getMessage msg-update)
                 _            (.hasText msg)
                 op-chat-id   (.getChatId msg)
                 user-msg-id  (.getMessageId msg)
                 msg-contents (.getText msg)]
       (try
         (exec (typing-action op-chat-id))
         (input-processor-fn op-chat-id user-msg-id msg-contents)
         (catch TelegramApiException e
           (.printStackTrace e))))
     nil)))

(defn- start-consumer [{:keys [input-processor-fn]}]
  (let [bot (TelegramBotsLongPollingApplication.)]
    (println "Starting Telegram consumer...")
    (.registerBot bot bot-token (create-bot input-processor-fn))
    bot))

(defn- stop-consumer [client]
  (println "Stopping Telegram consumer...")
  (.close client))

(defstate http-client :start (start-http-client))
(defstate consumer :start (start-consumer (mount/args)) :stop (stop-consumer consumer))

(defn- typing-action [^String id]
  (-> (SendChatAction/builder)
      (.action (.toString ActionType/TYPING))
      (.chatId (str id))
      (.build)))

(defn- send-msg [enable-markdown ^String id msg-id msg]
  (doto (-> (SendMessage/builder)
            (.chatId (str id))
            (.replyToMessageId msg-id)
            (.text msg)
            (.build))
    (.enableMarkdown enable-markdown)))

(defn- edit-msg [enable-markdown ^String op-id msg-id msg]
  (doto (-> (EditMessageText/builder)
            (.chatId (str op-id))
            (.messageId msg-id)
            (.text msg)
            (.build))
    (.enableMarkdown enable-markdown)))

(defn- ^Message exec [method]
  (.execute http-client method))

(defn send-first-response [chat-id user-msg-id chunk eof?]
  (exec (typing-action chat-id))
  (->> chunk
       (send-msg eof? chat-id user-msg-id)
       (exec)
       (.getMessageId)))

(defn send-updated-response [current-response-msg-id chat-id chunk eof?]
  (exec (typing-action chat-id))
  (exec (edit-msg eof? chat-id current-response-msg-id chunk)))

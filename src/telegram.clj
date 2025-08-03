(ns telegram
  (:require [mount.core :refer [defstate]]
            [openai]
            [config :as c]
            [threads :as t]
            [sundry :refer [when-let*]])
  (:import
    [org.telegram.telegrambots.client.jetty JettyTelegramClient]
    [org.eclipse.jetty.client HttpClient]
    [org.eclipse.jetty.client.transport HttpClientTransportOverHTTP]
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
(declare create-bot)

(defn start-http-client []
  (println "Starting Telegram HTTP client...")
  (-> (HttpClient. (HttpClientTransportOverHTTP. 1))
      (doto
        (.setMaxConnectionsPerDestination max-connections)
        (.setMaxRequestsQueuedPerDestination max-requests)
        (.setConnectTimeout connect-timeout)
        (.setExecutor (t/jetty-vthread-pool))
        (.start))
      (JettyTelegramClient. bot-token)))

(defn start-consumer []
  (let [bot (TelegramBotsLongPollingApplication.)]
    (println "Starting Telegram consumer...")
    (.registerBot bot bot-token (create-bot bot-token))
    bot))

(defn stop-consumer [client]
  (println "Stopping Telegram consumer...")
  (.close client))

(defstate http-client :start (start-http-client))
(defstate consumer :start (start-consumer) :stop (stop-consumer consumer))

(defn typing-action [^String id]
  (-> (SendChatAction/builder)
      (.action (.toString ActionType/TYPING))
      (.chatId id)
      (.build)))

(defn send-msg [^String id msg-id msg]
  (-> (SendMessage/builder)
      (.chatId id)
      (.replyToMessageId msg-id)
      (.text msg)
      (.build)))

(defn edit-msg [^String op-id msg-id msg]
  (-> (EditMessageText/builder)
      (.chatId op-id)
      (.messageId msg-id)
      (.text msg)
      (.build)))

(defn exec [client method]
  (.execute client method))

(defn chunked-response [client op-chat-id msg-id self-response-chat-id chunk]
  (when-not (empty? chunk)
    (if (nil? @self-response-chat-id)
      (do
        (exec client (typing-action op-chat-id))
        (->> chunk
             (send-msg op-chat-id msg-id)
             (exec client)
             (.getMessageId)
             (reset! self-response-chat-id)))
      (do
        (exec client (typing-action op-chat-id))
        (exec client (edit-msg op-chat-id @self-response-chat-id chunk))))))

(defn create-bot [token]
  (reify LongPollingSingleThreadUpdateConsumer
    (^void consume [^LongPollingSingleThreadUpdateConsumer _ ^Update msg-update]
      (when-let* [_ (.hasMessage msg-update)
                  msg (.getMessage msg-update)
                  msg-id (.getMessageId msg)
                  _ (.hasText msg)
                  chat-id (.getChatId msg)
                  msg-contents (.getText msg)
                  self-response-chat-id (atom nil)]
                 (try
                   (openai/chat-completion-streaming
                     openai/gpt-4
                     msg-contents
                     (partial chunked-response http-client (str chat-id) msg-id self-response-chat-id))
                   (catch TelegramApiException e
                     (.printStackTrace e))))
      nil)))                                                ;; Explicitly return nil for void method

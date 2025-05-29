(ns telegram
  (:require [mount.core :refer [defstate]]
            [openai]
            [config :as c]
            [sundry :refer [when-let*]])
  (:import
   [org.telegram.telegrambots.client.okhttp OkHttpTelegramClient]
   [org.telegram.telegrambots.meta.exceptions TelegramApiException]
   [org.telegram.telegrambots.meta.generics TelegramClient]
   [org.telegram.telegrambots.meta.api.methods.send SendMessage]
   [org.telegram.telegrambots.meta.api.methods.updatingmessages EditMessageText]
   [org.telegram.telegrambots.meta.api.objects Update]
   [org.telegram.telegrambots.meta.api.methods ActionType]
   [org.telegram.telegrambots.meta.api.methods.send SendChatAction]
   [org.telegram.telegrambots.longpolling.util LongPollingSingleThreadUpdateConsumer]
   [org.telegram.telegrambots.longpolling TelegramBotsLongPollingApplication]))

(def bot-token (-> (c/fetch) (c/telegram-bot-key)))

(defn typing-action [id]
  (-> (SendChatAction/builder)
      (.action (.toString ActionType/TYPING))
      (.chatId id)
      (.build)))

(defn send-msg [id msg]
  (-> (SendMessage/builder)
      (.chatId id)
      (.text msg)
      (.build)))

(defn edit-msg [op-id msg-id msg]
  (-> (EditMessageText/builder)
      (.chatId op-id)
      (.messageId msg-id)
      (.text msg)
      (.build)))

(defn exec [client method]
  (.execute client method))

(defn chunked-response [client op-chat-id self-response-chat-id chunk]
  (when-not (empty? chunk)
    (if (nil? @self-response-chat-id)
      (do
        (exec client (typing-action op-chat-id))
        (->> chunk
             (send-msg op-chat-id)
             (exec client)
             (.getMessageId)
             (reset! self-response-chat-id)))
      (do
        (exec client (typing-action op-chat-id))
        (exec client (edit-msg op-chat-id @self-response-chat-id chunk))))))

(defn create-bot [token]
  (let [client (OkHttpTelegramClient. token)]
    (reify LongPollingSingleThreadUpdateConsumer
      (^void consume [^LongPollingSingleThreadUpdateConsumer _ ^Update msg-update]
       (when-let* [_                     (.hasMessage msg-update)
                   msg                   (.getMessage msg-update)
                   _                     (.hasText msg)
                   chat-id               (.getChatId msg)
                   msg-contents          (.getText msg)
                   self-response-chat-id (atom nil)]
         (try
           (openai/chat-completion-streaming
            openai/gpt-4
            msg-contents
            (partial chunked-response client chat-id self-response-chat-id))
           (catch TelegramApiException e
             (.printStackTrace e))))
       nil)))) ;; Explicitly return nil for void method

(defn start []
  (let [bot (TelegramBotsLongPollingApplication.)]
    (.registerBot bot bot-token (create-bot bot-token))
    (println "Telegram bot successfully started!")
    bot))

(defn stop [client]
  (println "Telegram bot stopping...")
  (.close client))

(defstate client
  :start (start)
  :stop (stop client))


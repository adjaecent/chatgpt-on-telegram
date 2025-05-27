(ns telegram
  (:require [openai]
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
        (let [response (exec client (send-msg op-chat-id chunk))]
          (println "first response")
          (reset! self-response-chat-id (.getMessageId response))))
      (do
        (println (str "chunk is " chunk))
        (exec client (typing-action op-chat-id))
        (exec client (edit-msg op-chat-id @self-response-chat-id chunk))))))

(defn create-bot [token]
  (let [client (OkHttpTelegramClient. token)]
    (reify LongPollingSingleThreadUpdateConsumer
      (^void consume [^LongPollingSingleThreadUpdateConsumer _ ^Update msg-update]
       (when-let* [_ (.hasMessage msg-update)
                   msg (.getMessage msg-update)
                   _ (.hasText msg)
                   chat-id (.getChatId msg)
                   msg-contents (.getText msg)
                   self-response-chat-id (atom nil)]
         (try
           (openai/chat-completion-streaming
            openai/gpt-4
            msg-contents
            (partial chunked-response client chat-id self-response-chat-id))
           (catch TelegramApiException e
             (.printStackTrace e))))
       nil)))) ;; Explicitly return nil for void method

(defn start-dev []
  (let [bots-application (TelegramBotsLongPollingApplication.)]
    (.registerBot bots-application bot-token (create-bot bot-token))
    (println "Bot successfully started!")
    ;; Return the application so it can be closed later
    bots-application))

(comment
  (defn start [& args]
    (try
      (.registerBot (TelegramBotsLongPollingApplication.) bot-token (create-bot bot-token))
      (.join (Thread/currentThread))
      (catch Exception e
        (.printStackTrace e)))))

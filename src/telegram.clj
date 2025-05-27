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

;; TODO this is bad and will get rate-limited
;; buffer the chunks heavily before re-executing edits on Telegram
(defn chunked-response [client op-chat-id self-response-to-edit-chat-id chunk]
  (when-not (empty? chunk)
    (let [typing-action (-> (SendChatAction/builder)
                            (.action (.toString ActionType/TYPING))
                            (.chatId op-chat-id)
                            (.build))]
      (if (nil? (:id @self-response-to-edit-chat-id))
        (do
          (.execute client typing-action)
          (let [message (-> (SendMessage/builder)
                            (.chatId op-chat-id)
                            (.text chunk)
                            (.build))
                response (.execute client message)]
            (reset! self-response-to-edit-chat-id {:id (.getMessageId response) :msg chunk})))
        (do
          (.execute client typing-action)
          (swap! self-response-to-edit-chat-id update :msg str chunk)
          (let [message (-> (EditMessageText/builder)
                            (.chatId op-chat-id)
                            (.messageId (:id @self-response-to-edit-chat-id))
                            (.text (:msg @self-response-to-edit-chat-id))
                            (.build))]
            (.execute client message)))))))

(defn create-bot [token]
  (let [client (OkHttpTelegramClient. token)]
    (reify LongPollingSingleThreadUpdateConsumer
      (^void consume [^LongPollingSingleThreadUpdateConsumer _ ^Update msg-update]
       (when-let* [_ (.hasMessage msg-update)
                   msg (.getMessage msg-update)
                   _ (.hasText msg)
                   chat-id (.getChatId msg)
                   msg-contents (.getText msg)
                   self-response-to-edit-chat-id (atom {:id nil :msg nil})]
         (try
           (openai/chat-completion-streaming
            openai/client
            openai/gpt-4
            msg-contents
            (partial chunked-response client chat-id self-response-to-edit-chat-id))
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

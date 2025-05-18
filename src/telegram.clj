(ns telegram
  (:require [openai]
            [config :as c])
  (:import
   [org.telegram.telegrambots.client.okhttp OkHttpTelegramClient]
   [org.telegram.telegrambots.meta.exceptions TelegramApiException]
   [org.telegram.telegrambots.meta.generics TelegramClient]
   [org.telegram.telegrambots.meta.api.methods.send SendMessage]
   [org.telegram.telegrambots.meta.api.objects Update]
   [org.telegram.telegrambots.meta.api.methods ActionType]
   [org.telegram.telegrambots.meta.api.methods.send SendChatAction]
   [org.telegram.telegrambots.longpolling.util LongPollingSingleThreadUpdateConsumer]
   [org.telegram.telegrambots.longpolling TelegramBotsLongPollingApplication]))

(def bot-token (-> (c/fetch) (c/telegram-bot-key)))

(defn create-bot [token]
  (let [telegram-client (OkHttpTelegramClient. token)]
    (reify LongPollingSingleThreadUpdateConsumer
      (^void consume [^LongPollingSingleThreadUpdateConsumer this ^Update update]
        ;; Check if the update has a message and the message has text
        (when (and (.hasMessage update) (.hasText (.getMessage update)))
          ;; Set variables
          (let [message-text (.getText (.getMessage update))
                chat-id (.getChatId (.getMessage update))
                sub-fn (fn [chunk]
                         ;; Create a message object
                         (when-not (empty? chunk)
                           (let [message (-> (SendMessage/builder)
                                             (.chatId chat-id)
                                             (.text chunk)
                                             (.build))
                                 typing-action (-> (SendChatAction/builder)
                                                   (.action (.toString ActionType/TYPING))
                                                   (.chatId chat-id)
                                                   (.build))]
                             (.execute telegram-client typing-action)
                             (.execute telegram-client message))))]
            (try
              ;; Sending our message object to user
              (openai/chat-completion-streaming openai/client
                                                openai/gpt-4
                                                message-text
                                                sub-fn)
              (catch TelegramApiException e
                (.printStackTrace e)))))
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

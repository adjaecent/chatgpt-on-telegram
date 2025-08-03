(ns processor
  (:require [openai]
            [session]
            [telegram]))

(defn process-response
  ([session-id chunk]
   (process-response session-id chunk false))
  ([session-id chunk eof?]
   (when-not (empty? chunk)
     (let [{:keys [chat-id current-user-message-id current-response-message-id] :as session} (session/fetch session-id)]
       (if (nil? current-response-message-id)
         (let [response-chat-id (telegram/send-first-response chat-id current-user-message-id chunk (boolean eof?))]
           (session/write chat-id (assoc session :current-response-message-id response-chat-id)))
         (telegram/send-updated-response current-response-message-id chat-id chunk (boolean eof?)))))))

(defn process-user-input [chat-id user-msg-id prompt-content]
  (let [session (-> (or (session/fetch chat-id)
                        (session/new))
                    (assoc :chat-id chat-id)
                    (assoc :current-user-message-id user-msg-id)
                    (assoc :current-response-message-id nil)
                    (update :messages conj {:role :user :content prompt-content}))]
    (session/write chat-id session)
    (openai/chat-completion-streaming :general prompt-content (partial process-response chat-id))))
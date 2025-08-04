(ns processor
  (:require [clojure.string :as s]
            openai
            session
            telegram))

(defn process-model-stack-change [chat-id user-msg-id model-stack]
  (let [session (-> (or (session/fetch chat-id)
                        (session/new))
                    (assoc :chat-id chat-id)
                    (assoc :current-model-stack model-stack))]
    (session/write chat-id session)
    (telegram/send-first-response chat-id user-msg-id (str "You are now running the _" model-stack "_ model stack") true)))

(defmulti process-command (fn [_chat-id _user-msg-id command-str] (some-> command-str (s/split #"/") (last) (keyword))))

(defmethod process-command :fast [chat-id user-msg-id _] (process-model-stack-change chat-id user-msg-id :fast))

(defmethod process-command :reason [chat-id user-msg-id _] (process-model-stack-change chat-id user-msg-id :reason))

(defmethod process-command :code [chat-id user-msg-id _] (process-model-stack-change chat-id user-msg-id :code))

(defmethod process-command :general [chat-id user-msg-id _] (process-model-stack-change chat-id user-msg-id :general))

(defmethod process-command :reset [chat-id user-msg-id _]
  (session/write chat-id (session/new))
  (telegram/send-first-response chat-id user-msg-id "_Your session has been reset_ ⏱" true))

(defmethod process-command :default [chat-id user-msg-id _]
  (telegram/send-first-response chat-id user-msg-id "_Unknown command. Check the available list of commands from the command menu_ 🚫" true))

(defn- process-api-response
  ([session-id chunk]
   (process-api-response session-id chunk false))
  ([session-id chunk eof?]
   (when-not (empty? chunk)
     (let [{:keys [chat-id current-user-message-id] :as session} (session/fetch session-id)
           eof?                                                  (boolean eof?)]
       (if-let [current-response-message-id (:current-response-message-id session)]
         (telegram/send-edited-response current-response-message-id chat-id chunk eof?)
         (->> (telegram/send-first-response chat-id current-user-message-id chunk eof?)
              (assoc session :current-response-message-id)
              (session/write chat-id)))))))

(defn process-user-input [chat-id user-msg-id prompt-content]
  (if (s/starts-with? prompt-content "/")
    ;; --- IF a command, call the multimethod ---
    (process-command chat-id user-msg-id prompt-content)

    ;; --- ELSE, process it as a prompt ---
    (let [session (-> (or (session/fetch chat-id)
                          (session/new))
                      (assoc :chat-id chat-id)
                      (assoc :current-user-message-id user-msg-id)
                      (assoc :current-response-message-id nil)
                      (update :messages conj {:role :user :content prompt-content}))]
      (session/write chat-id session)
      (openai/chat-completion-streaming (:current-model-stack session)
                                        prompt-content
                                        (partial process-api-response chat-id)))))

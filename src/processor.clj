(ns processor
  (:require [clojure.string :as s]
            openai
            session
            telegram))

(defn process-model-stack-change [chat-id user-msg-id model-stack]
  (let [session (-> (session/fetch chat-id)
                    (assoc :chat-id chat-id)
                    (assoc :current-model-stack model-stack))]
    (session/write chat-id session)
    (telegram/send-first-response chat-id user-msg-id
                                  (str "You are now running the _" (name model-stack) "_ model stack")
                                  true)))

(defmulti process-command (fn [_chat-id _user-msg-id command-str] (some-> command-str (s/split #"/") (last) (keyword))))

(defmethod process-command :fast [chat-id user-msg-id _] (process-model-stack-change chat-id user-msg-id :fast))

(defmethod process-command :tech [chat-id user-msg-id _] (process-model-stack-change chat-id user-msg-id :tech))

(defmethod process-command :general [chat-id user-msg-id _] (process-model-stack-change chat-id user-msg-id :general))

(defmethod process-command :reset [chat-id user-msg-id _]
  (session/write chat-id)
  (telegram/send-first-response chat-id user-msg-id "_Your session has been reset_ ⏱" true))

(defmethod process-command :default [chat-id user-msg-id _]
  (telegram/send-first-response chat-id user-msg-id "_Unknown command. Check the available list of commands from the command menu_ 🚫" true))

(defn- process-new-response
  [{:keys [chat-id current-user-message-id] :as session} chunk eof?]
  (let [response-message-id (telegram/send-first-response chat-id current-user-message-id chunk eof?)
        updated-session (assoc session :current-response-message-id response-message-id)]
    (if eof?
      (session/write chat-id updated-session [(openai/msgfmt :assistant chunk)])
      (session/write chat-id updated-session))))

(defn- process-existing-response
  [{:keys [chat-id current-response-message-id] :as session} chunk eof?]
  (telegram/send-edited-response current-response-message-id chat-id chunk eof?)
  (when eof?
    (session/write chat-id session [(openai/msgfmt :assistant chunk)])))

(defn- openai->telegram
  ([session-id chunk]
   (openai->telegram session-id chunk false))
  ([session-id chunk eof?]
   (when-not (empty? chunk)
     (let [session (session/fetch session-id)
           eof? (boolean eof?)]
       (if-not (:current-response-message-id session)
         (process-new-response session chunk eof?)
         (process-existing-response session chunk eof?))))))

;; TODO: add a pre-prompt to sanitize the message to be relevant for telegram content size always
(defn input->openai [chat-id user-msg-id prompt-content]
  (if (s/starts-with? prompt-content "/")
    ;; --- IF a command, call the multimethod ---
    (process-command chat-id user-msg-id prompt-content)

    ;; --- ELSE, process it as a prompt ---
    (let [session (-> (session/fetch chat-id)
                      (assoc :chat-id chat-id)
                      (assoc :current-user-message-id user-msg-id)
                      (assoc :current-response-message-id nil))]
      (session/write chat-id session [(openai/msgfmt :user prompt-content)])
      (openai/chat-completion-streaming user-msg-id
                                        (:current-model-stack session)
                                        (session/fetch chat-id true)
                                        (partial openai->telegram chat-id)))))

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

(defn process-messages [{:keys [chat-id current-user-message-id current-response-message-ids] :as session} chunk-data eof?]
  (loop [chunks          chunk-data
         idx             0
         updated-session session
         reply-to        current-user-message-id]
    (if-let [{:keys [frozen value]} (first chunks)]
      (let [message (if (> idx 0) (str "_(continued...)_\n\n" value) value)]
        (if-let [id (get current-response-message-ids idx)]
          (do
            ;; Telegram API doesn't support editing message with the same text and markup type more than once
            (when (or (not frozen) eof?)
              (telegram/send-edited-response chat-id id message eof?))
            (recur (rest chunks)
                   (inc idx)
                   updated-session
                   id))
          (let [message-id (telegram/send-first-response chat-id reply-to message eof?)]
            (recur (rest chunks)
                   (inc idx)
                   (update updated-session :current-response-message-ids conj message-id)
                   message-id))))
      ;; once all chunks are processed, write the session
      (if eof?
        (session/write chat-id
                       updated-session
                       [(openai/msgfmt :assistant (s/join (map :value chunk-data)))])
        (session/write chat-id updated-session)))))

(defn- openai->telegram
  ([session-id chunks]
   (openai->telegram session-id chunks false))
  ([session-id chunks eof?]
   (when-not (empty? chunks)
     (prn "processing chunks" {:eof? (boolean eof?) :chunks (map (juxt :frozen (fn [c] (count (:value c)))) chunks)})
     (process-messages (session/fetch session-id) chunks (boolean eof?)))))

;; TODO: add a pre-prompt to sanitize the message to be relevant for telegram content size always
(defn input->openai [chat-id user-msg-id prompt-content]
  (if (s/starts-with? prompt-content "/")
    ;; --- IF a command, call the multimethod ---
    (process-command chat-id user-msg-id prompt-content)

    ;; --- ELSE, process it as a prompt ---
    (let [session (-> (session/fetch chat-id)
                      (assoc :chat-id chat-id)
                      (assoc :current-user-message-id user-msg-id)
                      (assoc :current-response-message-ids []))]
      (session/write chat-id session [(openai/msgfmt :user prompt-content)])
      (openai/chat-completion-streaming chat-id
                                        (:current-model-stack session)
                                        (session/fetch chat-id true)
                                        (partial openai->telegram chat-id)))))

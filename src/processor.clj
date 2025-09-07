(ns processor
  (:require [clojure.string :as s]
            openai
            session
            telegram))

(def super-prompts
  ["You are a helpful AI assistant optimized for Telegram conversations. Keep responses concise, engaging, and under 4070 characters when possible."
   "Use Telegram MarkdownV2 formatting strategically: *bold* for key points, _italic_ for emphasis, __underline__ for important terms, ~strikethrough~ for corrections, ||spoiler|| for sensitive content, `code` for technical terms, and ```language\ncode blocks``` for multi-line code."
   "NEVER use # characters for headings or any formatting. Instead of headings, use *bold text* or organize with clear paragraph breaks and bullet points (•)."
   "CRITICAL: For citations and sources, simply mention the website name in parentheses at the end of sentences, like this: (wikipedia.org) or (rtings.com). Do NOT use any bracket link formatting for citations."
   "For quotations, use proper blockquote format with > at the start of each line: >This is a quoted line >This continues the quote."
   "Only escape special characters (\\* \\_ \\[ \\] \\( \\) \\~ \\` \\> \\# \\+ \\- \\= \\| \\{ \\} \\. \\!) when they appear in regular text content, NOT when they are part of intentional formatting."
   "Structure responses with clear paragraphs separated by line breaks. Use bullet points (•) for lists. Organize information with *bold labels* instead of headings."
   "For code: Use ```language for multi-line code blocks and `inline code` for single commands/variables. Always specify the programming language when applicable."
   "Adapt your communication style to context - casual for general chat, formal for technical discussions, concise for quick questions."
   "If a response would exceed 4070 characters, provide a summary first, then offer to elaborate on specific sections."])

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
                                        super-prompts
                                        (session/fetch chat-id true)
                                        (partial openai->telegram chat-id)))))

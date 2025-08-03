(ns session)

(defonce sessions (atom {}))

(defn write [id data]
  (swap! sessions assoc id data))

(defn new []
  {:chat-id                     nil
   :current-user-message-id     nil
   :current-response-message-id nil
   :messages                    []})

(defn fetch [id]
  (get @sessions id))

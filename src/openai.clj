(ns openai
  (:require [mount.core :refer [defstate]]
            [config :as c]
            [threads :as t]
            [sundry :refer [buffer swp! rst! drn!]])
  (:import [com.openai.client.okhttp OpenAIOkHttpClient]
           [com.openai.core.http AsyncStreamResponse$Handler]
           [com.openai.models ChatModel]
           [com.openai.models.chat.completions ChatCompletionChunk ChatCompletionCreateParams]))

(def api-token (-> (c/fetch) (c/openai-key)))
(def gpt-4 ChatModel/GPT_4)
(def open-router-base-url "https://openrouter.ai/api/v1")
(defstate client
          :start (-> (OpenAIOkHttpClient/builder)
                     (.streamHandlerExecutor t/vthread-executor)
                     (.apiKey api-token)
                     (.baseUrl open-router-base-url)
                     (.build))
          :stop (.close client))

(defn parse-choice [choice]
  (-> choice
      (.delta)
      (.content)
      (.orElse nil)))

(defn choices [chunk] (.choices chunk))

(defn async-stream-handler [chunk-process-fn chunk-complete-fn]
  (reify AsyncStreamResponse$Handler
    (onNext [_ value]
      (chunk-process-fn value))
    (onComplete [this error]
      (chunk-complete-fn this error))))

(defn on-chunk-process [process-fn buffer]
  (fn [^ChatCompletionChunk chunk]
    (mapcat (fn [choice]
              (print "processing choice:" choice)
              (swp! buffer str (parse-choice choice))
              (process-fn @buffer))
            (choices chunk))))

(defn on-chunk-complete [_ error]
  (if (.isPresent error)
    (println "Completed chunk with error: " (.get error))
    (println "Completed chunk.")))

(defn on-stream-complete [process-fn buffer]
  (fn [_ error]
    (if error
      (println "Stream did not finish. Something went wrong!")
      (println "No more chunks left."))
    (process-fn (drn! buffer) :eof)))

(defn register-stream-initiate [stream chunk-process-fn buffer]
  (.subscribe stream (async-stream-handler (on-chunk-process chunk-process-fn buffer) on-chunk-complete)))

(defn register-stream-complete [stream chunk-process-fn buffer]
  (doto (.onCompleteFuture stream)
    (.whenComplete (on-stream-complete chunk-process-fn buffer))
    (deref)))

(defn chat-completion-streaming [^ChatModel model ^String msg on-chunk-process-fn]
  (let [params          (-> (ChatCompletionCreateParams/builder)
                            (.addUserMessage msg)
                            (.model model)
                            (.build))
        stream          (-> client
                            (.async)
                            (.chat)
                            (.completions)
                            (.createStreaming params))
        response-buffer (buffer)]
    (register-stream-initiate stream on-chunk-process-fn response-buffer)
    (register-stream-complete stream on-chunk-process-fn response-buffer)))

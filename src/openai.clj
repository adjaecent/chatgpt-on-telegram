(ns openai
  (:import [com.openai.client OpenAIClient]
           [com.openai.client.okhttp OpenAIOkHttpClient]
           [com.openai.core.http StreamResponse]
           [com.openai.models ChatModel]
           ;; [com.openai.core.http.AsyncStreamResponse Handler]
           [com.openai.models.chat.completions ChatCompletion ChatCompletionChunk ChatCompletionCreateParams]
           [java.util.concurrent CompletableFuture]
           [java.time Duration]))

(def api-key "")
(def client (-> (OpenAIOkHttpClient/builder) (.apiKey api-key) (.build)))

(defn chat-completion [^OpenAIClient client ^ChatModel model msg]
  (let [params (-> (ChatCompletionCreateParams/builder)
                   (.addUserMessage msg)
                   (.model model)
                   (.build))]
    (-> client
        (.async)
        (.chat)
        (.completions)
        (.create params))))

(defn async-stream-handler [chunk-process-fn chunk-complete-fn]
  (reify com.openai.core.http.AsyncStreamResponse$Handler
    (onNext [this value]
      (chunk-process-fn value))
    (onComplete [this error]
      (chunk-complete-fn this error))))

(defn on-chunk-process [^ChatCompletionChunk chunk]
  (mapcat (fn [choice]
            (print (-> choice
                       (.delta)
                       (.content)
                       (.orElse nil))))
          (.choices chunk)))

(defn on-chunk-complete [unused error]
  (if (.isPresent error)
    (println "Completed chunk with error: " (.get error))
    (println "Completed chunk.")))

(defn on-stream-complete [unused error]
  (if error
    (println "Stream did not finish. Something went wrong!")
    (println "No more chunks left.")))

(defn chat-completion-streaming [^OpenAIClient client ^ChatModel model msg]
  (let [params (-> (ChatCompletionCreateParams/builder)
                   (.addUserMessage msg)
                   (.model model)
                   (.build))
        stream (-> client
                   (.async)
                   (.chat)
                   (.completions)
                   (.createStreaming params))]
    (.subscribe stream (async-stream-handler on-chunk-process on-chunk-complete))
    (doto (.onCompleteFuture stream)
      (.whenComplete on-stream-complete)
      (deref))))

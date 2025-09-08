(ns openai
  (:require [config :as c]
            [mount.core :refer [defstate]]
            [sundry :refer [buffer drn! upd!]]
            [threads :as t])
  (:import com.openai.client.okhttp.OpenAIOkHttpClient
           com.openai.core.http.AsyncStreamResponse$Handler
           com.openai.core.JsonValue
           [com.openai.models.chat.completions ChatCompletionAssistantMessageParam ChatCompletionChunk ChatCompletionCreateParams ChatCompletionMessageParam ChatCompletionUserMessageParam ChatCompletionSystemMessageParam]
           com.openai.models.ChatModel))

(def open-router-base-url "https://openrouter.ai/api/v1")
(def api-token (-> (c/fetch) (c/openai-key)))
(def model-stacks {:fast    ["google/gemini-2.5-flash-lite", "meta-llama/llama-guard-2-8b"],
                   :general ["openai/gpt-4.1:online" "openai/gpt-4.1"],
                   :tech    ["anthropic/claude-3.7-sonnet:online" "anthropic/claude-3.7-sonnet"]})

;; unused stacks:
;; :reason  ["google/gemini-2.5-pro:online" "google/gemini-2.5-pro"]

(defstate client
  :start (-> (OpenAIOkHttpClient/builder)
             (.streamHandlerExecutor t/vthread-executor)
             (.apiKey api-token)
             (.baseUrl open-router-base-url)
             (.build))
  :stop (.close client))

(defn msgfmt [role message]
  {:role role :content message})

(defn msg->param [{:keys [role content]}]
  (case role
    :system    (ChatCompletionMessageParam/ofSystem
                (-> (ChatCompletionSystemMessageParam/builder)
                    (.content content)
                    (.build)))
    :user      (ChatCompletionMessageParam/ofUser
                (-> (ChatCompletionUserMessageParam/builder)
                    (.content content)
                    (.build)))
    :assistant (ChatCompletionMessageParam/ofAssistant
                (-> (ChatCompletionAssistantMessageParam/builder)
                    (.content content)
                    (.build)))))

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

;; TODO: only pick the first choice, instead of joining both
(defn on-chunk-process [process-fn buffer]
  (fn [^ChatCompletionChunk chunk]
    (mapcat (fn [choice]
              (upd! buffer (parse-choice choice))
              (process-fn @buffer))
            (choices chunk))))

(defn on-chunk-complete [process-fn buffer]
  (fn [_ error]
    (if (.isPresent error)
      (println "Completed chunk with error: " (.get error))
      (do
        (process-fn (drn! buffer) :eof)
        (println "Completed chunk. Drained the buffer.")))))

(defn on-stream-complete [_ error]
  (if error
    (println "Stream did not finish. Something went wrong!")
    (println "No more chunks left.")))

(defn register-stream-initiate [stream chunk-process-fn buffer]
  (.subscribe stream (async-stream-handler (on-chunk-process chunk-process-fn buffer)
                                           (on-chunk-complete chunk-process-fn buffer))))

(defn register-stream-complete [stream]
  (doto (.onCompleteFuture stream)
    (.whenComplete on-stream-complete)
    (deref)))

(defn chat-completion-streaming [user-id model-stack system-messages messages on-chunk-process-fn]
  (let [model-stack     (get model-stacks (or model-stack :fast))
        system-messages (map #(msgfmt :system %) system-messages)
        all-messages    (concat system-messages messages)
        params          (-> (ChatCompletionCreateParams/builder)
                            (.messages (map msg->param all-messages))
                            (.model (ChatModel/of (first model-stack)))
                            (.putAdditionalBodyProperty "stream", (JsonValue/from true))
                            (.putAdditionalBodyProperty "user", (JsonValue/from (str user-id)))
                            (.putAdditionalBodyProperty "models", (JsonValue/from model-stack))
                            (.putAdditionalBodyProperty "transforms", (JsonValue/from ["middle-out"]))
                            (.build))
        stream          (-> client
                            (.async)
                            (.chat)
                            (.completions)
                            (.createStreaming params))
        response-buffer (buffer)]
    (register-stream-initiate stream on-chunk-process-fn response-buffer)
    (register-stream-complete stream)))

(ns main
  (:require [mount.core :as mount]
            [datomic]
            [session-sweeper]
            openai
            processor
            telegram))

(defn add-shutdown-hook []
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. #(mount/stop))))

(defn start-deps []
  (mount/start #'openai/client
               #'telegram/http-client
               #'datomic/conn
               #'session-sweeper/sweeper)

  (mount/start-with-args
   {:input-processor-fn processor/process-user-input}
    #'telegram/consumer))

(defn -main
  "Start the telegram client"
  [& _args]
  (add-shutdown-hook)
  (start-deps)
  (.join (Thread/currentThread)))

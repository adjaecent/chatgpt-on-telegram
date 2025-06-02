(ns main
  (:require [mount.core :as mount]
            [openai]
            [telegram]))

(defn add-shutdown-hook []
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. mount/stop)))

(defn -main
  "Start the telegram client"
  [& _args]
  (add-shutdown-hook)
  (mount/start #'openai/client
               #'telegram/client)
  (.join (Thread/currentThread)))


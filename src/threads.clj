(ns threads
  (:import [java.util.concurrent Executors]))

(defonce vthread-executor (Executors/newVirtualThreadPerTaskExecutor))

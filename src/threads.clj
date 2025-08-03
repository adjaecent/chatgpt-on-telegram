(ns threads
  (:import [java.util.concurrent Executors]
           [org.eclipse.jetty.util VirtualThreads]
           [org.eclipse.jetty.util.thread VirtualThreadPool]))

(defonce vthread-executor
  ^{:doc "These are run on platform threads (common, carrier fork-join pool"}
  (Executors/newVirtualThreadPerTaskExecutor))

(defn jetty-vthread-pool
  "Pure virtual threads. Not run on platform threads.
  See https://jetty.org/docs/jetty/12/programming-guide/arch/threads.html#thread-pool-virtual-threads for more info
  VirtualThreadPool doesn't actually pool, so we enforce a max limit"
  []
  (doto (VirtualThreadPool.)
    (.setMaxThreads 200)
    (.setName "jetty-vthread")))

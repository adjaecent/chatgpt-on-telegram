(ns main)

(defn -main
  "I say hello to the world."
  [& args]
  (println "Hello, World!"))

(when (= *file* (System/getProperty "java.class.path"))
  (-main))

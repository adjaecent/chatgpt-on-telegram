(ns sundry)

(defmacro when-let*
  ([bindings & body]
   (if (seq bindings)
     `(when-let [~(first bindings) ~(second bindings)]
        (when-let* ~(drop 2 bindings) ~@body))
     `(do ~@body))))

(defprotocol ISinkOps
  (swp!
    [buf f]
    [buf f x]
    [buf f x y]
    [buf f x y & args])
  (rst! [buf newval]))

(deftype TSink [^clojure.lang.Atom iatom ^long threshold]
  clojure.lang.IDeref
  (deref [_]
    (let [val @iatom]
      (when (>= (count val) threshold)
        val)))
  ISinkOps
  (swp! [buf f] (swap! (.iatom buf) f))
  (swp! [buf f x] (swap! (.iatom buf) f x))
  (swp! [buf f x y] (swap! (.iatom buf) f x y))
  (swp! [buf f x y & args] (apply swap! (.iatom buf) f x y args))
  (rst! [buf newval] (reset! (.iatom buf) newval)))

(defmethod print-method TSink [buf ^java.io.Writer w]
  (.write w "#<TBuffer: ")
  (print-method @buf w)
  (.write w (str "> Threshold value: " (.threshold buf))))

(defn sink
  "Creates an atom-like interface that stores a sequence of characters.
  Only deferences when the supplied threshold is met. Defaults to a size of 200."
  ([] (sink 200))
  ([threshold] (TSink. (atom "") threshold)))

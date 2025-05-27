(ns sundry)

(defmacro when-let*
  ([bindings & body]
   (if (seq bindings)
     `(when-let [~(first bindings) ~(second bindings)]
        (when-let* ~(drop 2 bindings) ~@body))
     `(do ~@body))))

(defprotocol IBufferOps
  (swp!
    [buf f]
    [buf f x]
    [buf f x y]
    [buf f x y & args])
  (rst! [buf newval]))

(deftype TBuffer [^clojure.lang.Atom iatom ^clojure.lang.Atom last-deref-multiplier-atom ^long threshold]
  clojure.lang.IDeref
  (deref [_]
    (let [current-val @iatom
          current-count (count current-val)
          last-multiple @last-deref-multiplier-atom
          target-multiple (inc last-multiple)
          target-size (* target-multiple threshold)]
      (when (>= current-count target-size)
        (reset! last-deref-multiplier-atom (quot current-count threshold))
        current-val)))
  IBufferOps
  (swp! [buf f] (swap! (.iatom buf) f))
  (swp! [buf f x] (swap! (.iatom buf) f x))
  (swp! [buf f x y] (swap! (.iatom buf) f x y))
  (swp! [buf f x y & args] (apply swap! (.iatom buf) f x y args))
  (rst! [buf newval]
    (reset! (.iatom buf) newval)
    (reset! (.last-deref-multiplier-atom buf) 0)
    newval))

(defmethod print-method TBuffer [buf ^java.io.Writer w]
  (.write w (str "#<TBuffer thres=" (.threshold buf) ",cur=" (count @(.iatom buf)) ">")))

(defn buffer
  "Creates an atom-like interface that stores a sequence of characters.
  Only dereferences when the content length meets or exceeds the threshold
  for the first time, or subsequently when it reaches the next multiple
  of the threshold. After a successful dereference, the internal counter
  is updated based on the current content length divided by the threshold.
  Defaults to a threshold of 200."
  ([] (buffer 200))
  ([threshold] (TBuffer. (atom "") (atom 0) threshold)))

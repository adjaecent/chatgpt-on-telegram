(ns sundry)

(defmacro when-let*
  ([bindings & body]
   (if (seq bindings)
     `(when-let [~(first bindings) ~(second bindings)]
        (when-let* ~(drop 2 bindings) ~@body))
     `(do ~@body))))

(defn between? [x a b]
  (and (>= x a) (<= x b)))

(defprotocol IBufferOps
  (swp!
    [buf f]
    [buf f x]
    [buf f x y]
    [buf f x y & args])
  (rst! [buf newval])
  (drn! [buf]))

(deftype TBuffer [^clojure.lang.Atom iatom ^clojure.lang.Atom last-deref-multiple-atom ^long threshold]
  clojure.lang.IDeref
  (deref [_]
    (let [current-val   @iatom
          current-count (count current-val)
          last-multiple @last-deref-multiple-atom
          three-percent (* threshold 0.03)
          eight-percent (* threshold 0.08)
          target-size   (* (inc last-multiple) threshold)]
      (cond
        ;; when it's the first deref attempt, and the buffer is between 3-8% of threshold
        ;; deref immediately
        (and (== last-multiple -1)
             (between? current-count three-percent eight-percent))
        (do (reset! last-deref-multiple-atom 0)
            current-val)

        ;; otherwise, deref when we've buffered the next multiple of threshold
        (and (> last-multiple -1)
             (>= current-count target-size))
        (do (reset! last-deref-multiple-atom (quot current-count threshold))
            current-val))))
  IBufferOps
  (swp! [buf f] (swap! (.iatom buf) f))
  (swp! [buf f x] (swap! (.iatom buf) f x))
  (swp! [buf f x y] (swap! (.iatom buf) f x y))
  (swp! [buf f x y & args] (apply swap! (.iatom buf) f x y args))
  (rst! [buf newval]
    (reset! (.iatom buf) newval)
    (reset! (.last-deref-multiple-atom buf) -1)
    newval)
  (drn! [buf] @(.iatom buf)))

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
  ([threshold] (TBuffer. (atom "") (atom -1) threshold)))

(ns sundry)

(defn safe-parse-long [n]
  (try
    (parse-long n)
    (catch Exception _ nil)))

(defn safe-parse-int [n]
  (try
    (Integer/parseInt n)
    (catch Exception _ nil)))

(defmacro when-let*
  ([bindings & body]
   (if (seq bindings)
     `(when-let [~(first bindings) ~(second bindings)]
        (when-let* ~(drop 2 bindings) ~@body))
     `(do ~@body))))

(defn between? [x a b]
  (and (>= x a) (<= x b)))

(defn text-excess [text threshold]
  (let [len (count text)
        split-point (min threshold len)]
    [(subs text 0 split-point)
     (subs text split-point)]))

(defn replace-last [original-coll new-coll]
  (into (pop original-coll) new-coll))

(defn add-to-last [coll s]
 (if (empty? coll)
   [s]
   (conj (pop coll) (str (last coll) s))))

(defprotocol IBufferOps
  (upd! [buf s])
  (mut! [buf newval])
  (drn! [buf]))

(deftype TBuffer [^clojure.lang.Atom iatom ^clojure.lang.Atom last-deref-multiple-atom ^long threshold ^long partition-at]
  clojure.lang.IDeref
  (deref [_]
    (let [current-val        @iatom
          last-partition-val (last current-val)
          current-count      (count last-partition-val)]

      ;; when the size exceeds partition-at, split the text
      (when (and (> partition-at 0)
                 (> current-count partition-at))
        (prn "size exceeded")
        (swap! iatom replace-last (text-excess last-partition-val partition-at))
        (reset! last-deref-multiple-atom 0))

      (prn @iatom)

      (let [current-val        @iatom
            last-partition-val (last current-val)
            current-count      (count last-partition-val)
            last-multiple      @last-deref-multiple-atom
            three-percent      (* threshold 0.03)
            eight-percent      (* threshold 0.08)
            target-size        (* (inc last-multiple) threshold)]

        (prn last-partition-val)
        (prn last-multiple)
        (prn current-count)

        (cond
          ;; when it's the first deref attempt, and the buffer is between 3-8% of threshold
          ;; deref immediately
          ;; this is an optimization for first-time early responses
          (and (== last-multiple -1)
               (between? current-count three-percent eight-percent))
          (do
            (reset! last-deref-multiple-atom 0)
            last-partition-val)

          ;; otherwise, deref when we've buffered the next multiple of threshold
          (and (> last-multiple -1)
               (>= current-count target-size))
          (do
            (reset! last-deref-multiple-atom (quot current-count threshold))
            last-partition-val)

          ;; reset the deref-multiple back to 0 so multiples can start working normally
          (== last-multiple -1)
          (do
            (reset! last-deref-multiple-atom 0)
            nil)

          :else nil))))

  IBufferOps
  (upd! [buf s] (swap! (.iatom buf) add-to-last s))
  (mut! [buf newval]
    (reset! (.iatom buf) [newval])
    (reset! (.last-deref-multiple-atom buf) -1)
    newval)
  (drn! [buf] @(.iatom buf)))

(defmethod print-method TBuffer [buf ^java.io.Writer w]
  (.write w (str "#<TBuffer thres=" (.threshold buf) ",cur=" (count @(.iatom buf)) ">")))

(defn buffer
  "
  Creates an atom-like interface that stores a sequence of characters.
  Only dereferences when the content length meets or exceeds the threshold
  for the first time, or subsequently when it reaches the next multiple
  of the threshold. After a successful dereference, the internal counter
  is updated based on the current content length divided by the threshold.
  Defaults to a threshold of 200."
  ([] (buffer 200 4096))
  ([threshold] (buffer threshold 4096))
  ([threshold partition-at] (TBuffer. (atom []) (atom -1) threshold partition-at)))

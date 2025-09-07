(ns sundry)

(defn safe-parse-long [n]
  (try
    (parse-long n)
    (catch Exception _ nil)))

(defn safe-parse-int [n]
  (if (int? n)
    n
    (try
      (Integer/parseInt n)
      (catch Exception _ nil))))

(defmacro when-let*
  ([bindings & body]
   (if (seq bindings)
     `(when-let [~(first bindings) ~(second bindings)]
        (when-let* ~(drop 2 bindings) ~@body))
     `(do ~@body))))

(defn text-excess [text threshold]
  (let [len         (count text)
        split-point (min threshold len)]
    (when-not (empty? text)
      (concat [(subs text 0 split-point)]
              (text-excess (subs text split-point) threshold)))))

(defprotocol IBufferOps
  (upd! [buf s])
  (mut! [buf newval])
  (drn! [buf]))

(deftype TBuffer [^clojure.lang.Atom iatom ^clojure.lang.Atom last-deref-multiple-atom ^long threshold ^long partition-at]
  clojure.lang.IDeref
  (deref [_]
    (let [current-val        @iatom
          last-partition-val (:value (last current-val))
          current-count      (count last-partition-val)]

      ;; when the size exceeds partition-at, split the text
      (when (and (> partition-at 0)
                 (> current-count partition-at))
        (letfn [(replace-last [original new-vals]
                  (into (pop original)
                        (map (fn [v] {:frozen false :value v}) new-vals)))]
          (swap! iatom replace-last (text-excess last-partition-val partition-at)))
        (reset! last-deref-multiple-atom 0))

      (let [current-val        @iatom
            last-partition-val (:value (last current-val))
            current-count      (count last-partition-val)
            last-multiple      @last-deref-multiple-atom
            eight-percent      (* threshold 0.08)
            target-size        (* (inc last-multiple) threshold)]

        (cond
          ;; when it's the first deref attempt, and the buffer is between 3-8% of threshold
          ;; deref immediately
          ;; this is an optimization for first-time early responses
          (and (== last-multiple -1)
               (>= current-count eight-percent))
          (do
            (reset! last-deref-multiple-atom (quot current-count threshold))
            current-val)

          ;; otherwise, deref when we've buffered the next multiple of threshold
          (and (> last-multiple -1)
               (>= current-count target-size))
          (do
            (reset! last-deref-multiple-atom (quot current-count threshold))
            current-val)

          :else nil))))

  IBufferOps
  (upd! [buf s] (letfn [(add-to-last [coll s]
                          (if (empty? coll)
                            [{:frozen false :value s}]
                            (conj (mapv #(assoc % :frozen true) (pop coll))
                                  (update (last coll) :value str s))))]
                  (swap! (.iatom buf) add-to-last s)))
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
  ([] (buffer 200 4070))
  ([threshold] (buffer threshold 4070))
  ([threshold partition-at] (TBuffer. (atom []) (atom -1) threshold partition-at)))

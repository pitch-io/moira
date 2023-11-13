(ns moira.log.event-emitter
  (:require [moira.event :as event]))

(defprotocol Listenable
  (emit [this e])
  (listen [this f] [this k f])
  (unlisten [this] [this f] [this k f]))

(defprotocol Resumable
  (pause [this])
  (resume [this]))

(def ^:private empty-registry {::any []})

(defn- map-values [m f]
  (into {} (map (fn [[k v]] [k (f v)])) m))

(defn- remove-item [v x]
  (into [] (remove (partial = x)) v))

(defn- fire [{:keys [type] :as event} {::keys [any] :as registry}]
  (doseq [f (get registry type any)] (f event)))

(defn- emit* [this event registry]
  (pause this)
  (try
    (fire event registry)
    (finally (resume this))))

(deftype EventEmitter [factory !registry !buffer !paused?]
  Listenable
  (emit [this e]
    (let [e* (event/->event factory e)]
      (if @!paused?
        (vswap! !buffer conj e*)
        (emit* this e* @!registry))
      e*))
  (listen [_ f]
    (vswap! !registry map-values #(conj % f)))
  (listen [_ k f]
    (vswap! !registry (fn [{::keys [any] :as r}]
                        (update r k #(conj (or % any) f)))))
  (unlisten [_]
    (vreset! !registry empty-registry))
  (unlisten [_ f]
    (vswap! !registry map-values #(remove-item % f)))
  (unlisten [_ k f]
    (vswap! !registry update k #(remove-item % f)))
  Resumable
  (pause [_]
    (vreset! !paused? true))
  (resume [_]
    (while (seq @!buffer)
      (fire (peek @!buffer) @!registry)
      (vswap! !buffer pop))
    (vreset! !paused? false)))

(defn create []
  (->EventEmitter (event/factory)
                  (volatile! empty-registry)
                  (volatile! #queue [])
                  (volatile! false)))

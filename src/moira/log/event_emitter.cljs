(ns moira.log.event-emitter
  "Create the Application Log, a central hub for propagating Application
  Events."

  (:require [moira.event :as event]))

(defprotocol Listenable
  "Provide means to subscribe to or unsubscribe from events of a specific
  type."

  (emit [this e]
    "Dispatch event `e` to all listeners.

    `:type` should be defined as keyword.")

  (listen [this f] [this k f]
    "Register `f` to be notified about events of type `k`.

    If `k` is not given trigger `f` on all events.")

  (unlisten [this] [this f] [this k f]
    "Unregister `f` to no longer be triggered on events of type `k`.

    If `k` is not given unregister `f` from all events. If both `k` and `f` are
    not specified unregister all handlers from all events."))

(defprotocol Resumable
  "Pause dispatching events and buffer events to notify handlers on resume."

  (pause [this]
    "Pause dispatching of Application Events.

    Buffer events for later dispatch.")

  (resume [this]
    "Resume dispatching of Application Events.

    All buffered events are triggered first and in order."))

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

(deftype
 ^{:doc
   "Produce Application Events and propagate them to registered listeners.

   Dispatching of events can be paused and resumed."}

 EventEmitter

 [factory !registry !buffer !paused?]

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

(defn create
  "Create [[EventEmitter]] instance to serve as Application Log."

  []

  (->EventEmitter (event/factory)
                  (volatile! empty-registry)
                  (volatile! #queue [])
                  (volatile! false)))

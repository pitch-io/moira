(ns moira.log.txs
  "Interceptors for integrating `:app-log` into the
  [[moira.transition|transition]] flow."

  (:require [moira.log.event-emitter :as event-emitter]
            [moira.log.module :as log.module]
            [moira.transition :as-alias transition]))

(defn- inject* [app]
  (into {}
        (map (fn [[k v]]
               [k (cond-> v
                    (not= :app-log k)
                    (update :deps (comp set conj) :app-log))]))
        (update app :app-log #(merge log.module/default %))))

(def inject
  "[[moira.context|Interceptor]] for ensuring `:app-log` exists and the
  Application Log API is available to every module.

  Merges `:app-log` with the [[moira.log.module/default|default
  implementation]] while keeping existing values unchanged. Adds `:app-log` to
  each other module's `:deps`."

  {:name ::inject
   :enter (fn [ctx]
            (update ctx ::transition/app inject*))})

(defn- doto-event-emitter [ctx f]
  (when-let [{:keys [event-emitter]}
             (get-in ctx [::transition/app :app-log :state])]
    (f event-emitter))
  ctx)

(def pause
  "[[moira.context|Interceptor]] to pause Application Events from being
  triggered during a [[moira.transition|transition]].

  Buffers all incoming [[moira.event|events]] until the transition has finished
  or was canceled due to an error. Will flush the buffer by triggering to
  Application Log once the system has settled."

  {:name ::pause
   :enter (fn [ctx]
            (doto-event-emitter ctx event-emitter/pause))
   :error (fn [ctx]
            (doto-event-emitter ctx event-emitter/resume))
   :leave (fn [ctx]
            (doto-event-emitter ctx event-emitter/resume))})

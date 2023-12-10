(ns moira.log.txs
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
  {:name ::inject
   :enter (fn [ctx]
            (update ctx ::transition/app inject*))})

(defn- doto-event-emitter [ctx f]
  (when-let [{:keys [event-emitter]}
             (get-in ctx [::transition/app :app-log :state])]
    (f event-emitter))
  ctx)

(def pause
  {:name ::pause
   :enter (fn [ctx]
            (doto-event-emitter ctx event-emitter/pause))
   :error (fn [ctx]
            (doto-event-emitter ctx event-emitter/resume))
   :leave (fn [ctx]
            (doto-event-emitter ctx event-emitter/resume))})

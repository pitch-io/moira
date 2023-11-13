(ns moira.log.txs
  (:require [moira.context :as context]
            [moira.log.event-emitter :as event-emitter]
            [moira.log.module :as log.module]
            [moira.module :as module]))

(defn ensure-dependency [k]
  {:name ::ensure-dependency
   :enter (fn [{::module/keys [current] :as ctx}]
            (cond-> ctx
              (not= current k)
              (update-in [:moira.transition/app current :deps]
                         (comp set conj)
                         k)))})

(def inject
  {:name ::inject
   :enter (fn [ctx]
            (-> ctx
                (update-in [:moira.transition/app :app-log]
                           (partial merge log.module/default))
                (update :moira.transition/modules
                        context/into-queue
                        [:app-log])
                (update :moira.transition/txs
                        context/into-queue
                        [(ensure-dependency :app-log)])))})

(defn- doto-event-emitter [ctx f]
  (when-let [{:keys [event-emitter]}
             (get-in ctx [:moira.transition/app :app-log :state])]
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

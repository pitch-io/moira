(ns moira.log.txs
  (:require [moira.context :as context]
            [moira.log.event-emitter :as event-emitter]
            [moira.log.module :as log.module]
            [moira.module :as module]
            [moira.transition :as-alias transition]))

(def ensure-dependency
  {:name ::ensure-dependency
   :enter (fn [{::module/keys [current] :as ctx}]
            (cond-> ctx
              (not= current :app-log)
              (update-in [::transition/app current :deps]
                         (comp set conj)
                         :app-log)))})

(defn- enqueue-if-missing [xs x]
  (context/into-queue (if (some #{x} xs) [] [x]) xs))

(def inject
  {:name ::inject
   :enter (fn [ctx]
            (-> ctx
                (update-in [::transition/app :app-log]
                           (partial merge log.module/default))
                (update ::transition/modules
                        enqueue-if-missing
                        :app-log)
                (update ::transition/txs
                        enqueue-if-missing
                        ensure-dependency)))})

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

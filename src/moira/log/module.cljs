(ns moira.log.module
  (:require [clojure.spec.alpha :as s]
            [moira.log.event-emitter :as event-emitter]))

(defn start [state]
  (assoc state :event-emitter (event-emitter/create)))

(defn stop [{:keys [event-emitter]}]
  (event-emitter/unlisten event-emitter)
  nil)

(s/def ::export (s/map-of #{:off :on :put} ifn?))

(defn export [{:keys [event-emitter]}]

  {:post [(s/valid? ::export %)]}

  {:off (partial event-emitter/unlisten event-emitter)
   :on (partial event-emitter/listen event-emitter)
   :put (partial event-emitter/emit event-emitter)})

(def default {:export #'export
              :start #'start
              :stop #'stop})

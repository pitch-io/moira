(ns moira.log.module
  "Instrument Application Log by setting up
  [[moira.log.event-emitter/EventEmitter|EventEmitter]] as a module."

  (:require [clojure.spec.alpha :as s]
            [moira.log.event-emitter :as event-emitter]))

(defn start
  "Create an [[moira.log.event-emitter/EventEmitter|EventEmitter] instance and
  store it as part of the module's `state`."

  [state]

  (assoc state :event-emitter (event-emitter/create)))

(defn stop
  "Wrap up [[moira.log.event-emitter/EventEmitter|EventEmitter] instance and
  clear module's `state`."

  [{:keys [event-emitter]}]

  (event-emitter/unlisten event-emitter)
  nil)

(s/def ::export (s/map-of #{:off :on :put} ifn?))

(defn export
  "Export API for interacting with the Application Log."

  [{:keys [event-emitter]}]

  {:post [(s/valid? ::export %)]}

  {:off (partial event-emitter/unlisten event-emitter)
   :on (partial event-emitter/listen event-emitter)
   :put (partial event-emitter/emit event-emitter)})

(def default
  "Defaults that will automatically be injected into each
  [[moira.application/Application|Application]] as `:app-log`."

  {:export #'export
   :start #'start
   :stop #'stop})

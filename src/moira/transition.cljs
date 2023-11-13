(ns moira.transition
  (:require [clojure.spec.alpha :as s]
            [moira.context :as context]
            [moira.log.txs :as log.txs]
            [moira.module :as module]
            [promesa.core :as p]))

(def all? (partial = :all))

(s/def ::module-ks (s/or :coll coll? :all all?))

(defn- resolve-module-ks [app module-ks]
  (if (all? module-ks) (keys app) module-ks))

(defn enqueue-modules-with-deps [modules]
  {:name ::enqueue-modules-with-deps
   :enter (fn [{::keys [app] :as ctx}]
            (->> modules
                 (resolve-module-ks app)
                 (module/dependency-chain app)
                 (update ctx ::modules context/into-queue)))})

(defn enqueue-modules [modules]
  {:name ::enqueue-modules
   :enter (fn [{::keys [app] :as ctx}]
            (->> modules
                 (resolve-module-ks app)
                 (update ctx ::modules context/into-queue)))})

(def reverse-modules
  {:name ::reverse-modules
   :enter (fn [ctx]
            (update ctx ::modules (partial into [])))})

(defn execute-txs-1 [{::keys [modules txs] :as ctx}]
  (p/-> ctx
        (assoc ::modules (pop modules))
        (module/execute (peek modules) txs)))

(defn execute-txs [txs]
  {:name ::execute-txs
   :enter (fn [ctx]
            (p/loop [{::keys [modules] :as ctx}
                     (update ctx ::txs context/into-queue txs)]
              (if (empty? modules)
                ctx
                (p/recur (execute-txs-1 ctx)))))})

(def ^:private n (namespace ::_))

(defn execute [app txs]
  (p/-> {::app app}
        (context/execute n txs)
        (get ::app)))

(defn up
  "Elevate modules defined in `ks` and all their dependencies by applying the
  interceptor chain `txs` in the context of each module respectively. Returns a
  new application with updated module states.

  Dependencies are guaranteed to be updated first. `:app-log` is injected, if
  not already present, and automatically added to each module's `:deps`. When a
  circular dependency is detected, an error is thrown and no updates are
  applied."

  [app txs ks]

  {:pre [(s/valid? ::module/system-map app)
         (s/valid? ::context/txs txs)
         (s/valid? ::module-ks ks)]}

  (execute app [log.txs/inject
                log.txs/pause
                (enqueue-modules-with-deps ks)
                (execute-txs txs)]))

(defn down

  "Degrade modules defined in `ks` and all their dependencies by applying the
  interceptor chain `txs` in the context of each module respectively. Returns a
  new application with updated module states.

  Dependencies are guaranteed to be updated in reverse order. When a circular
  dependency is detected, an error is thrown and no updates are applied."

  [app txs ks]

  {:pre [(s/valid? ::module/system-map app)
         (s/valid? ::context/txs txs)
         (s/valid? ::module-ks ks)]}

  (execute app [log.txs/pause
                (enqueue-modules-with-deps ks)
                reverse-modules
                (execute-txs txs)]))

(defn tx

  "Update modules defined in `ks` by applying the interceptor chain `txs` in
  the context of each module respectively. Returns a new application with
  updated module states.

  Dependencies are not updated. `:app-log` is expected to be present already."

  [app txs ks]

  {:pre [(s/valid? ::module/system-map app)
         (s/valid? ::context/txs txs)
         (s/valid? ::module-ks ks)]}

  (execute app [log.txs/pause
                (enqueue-modules ks)
                (execute-txs txs)]))

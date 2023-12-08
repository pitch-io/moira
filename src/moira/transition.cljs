(ns moira.transition
  "Transform `app` by wrapping it with a [[moira.context|context]] for
  execution of the interceptor chain `txs` on each module in order. In this
  case, `app` refers to the immutable `system-map` directly and *not* to an
  instance of [[moira.application/Application|Application]].

  Dependencies are handled appropriately. When applying an [[up]] transition,
  they are inserted into the `::queue` upfront. Conversely, the transition is
  applied to dependencies subsequently when using the [[down]] command. Any
  circular dependency is detected ahead of execution and will throw an error.

  The [[moira.log|Application Log]] is temporarily paused during transitions.
  All Application Events are buffered and triggered at a later stage when the
  system has settled."

  (:require [clojure.spec.alpha :as s]
            [moira.context :as context]
            [moira.log.txs :as log.txs]
            [moira.module :as module]
            [promesa.core :as p]))

(defn- all? [ks]
  (= :all ks))

(s/def ::module-ks (s/or :coll coll? :all all?))

(defn- resolve-module-ks [app ks]

  {:pre [(s/valid? ::module-ks ks)]}

  (if (all? ks) (keys app) ks))

(defn enqueue-modules
  "Returns an [[moira.context|interceptor]] that inserts `ks` into `::modules`,
  consequently scheduling them as targets for execution."

  [ks]

  {:name ::enqueue-modules
   :enter (fn [{::keys [app] :as ctx}]
            (->> ks
                 (resolve-module-ks app)
                 (update ctx ::modules context/into-queue)))})

(defn enqueue-modules-with-deps
  "Returns an [[moira.context|interceptor]] that inserts `ks` and all their
  dependencies into `::modules`, consequently scheduling them as targets for
  execution.

  Order is determined by the dependency graph so that each module is guaranteed
  to be inserted after all its dependencies."

  [ks]

  {:name ::enqueue-modules-with-deps
   :enter (fn [{::keys [app] :as ctx}]
            (->> ks
                 (resolve-module-ks app)
                 (module/dependency-chain app)
                 (update ctx ::modules context/into-queue)))})

(def reverse-modules
  "[[moira.context|Interceptor]] that reverses the order of `::modules`
  scheduled as targets for execution.

  [[down]] uses this interceptor to ensure each dependency is targeted after
  all its dependent modules."

  {:name ::reverse-modules
   :enter (fn [ctx]
            (update ctx ::modules #(context/into-queue [] (reverse %))))})

(defn execute-txs-1
  "Dequeue the next item from `::modules` and [[moira.module/execute|execute]]
  the interceptor chain `::txs` targeting this module.

  Returns a `Promise` resolving to the updated [[moira.context|context]]
  `ctx`."

  [{::keys [modules txs] :as ctx}]

  (p/-> ctx
        (assoc ::modules (pop modules))
        (module/execute (peek modules) txs)))

(defn execute-txs
  "Returns an [[moira.context|interceptor]] for iterating over `::modules` to
  [[moira.module/execute|execute]] `txs` in the context of each module
  independently.

  The interceptor's `:enter` function will return a `Promise` that resolves to
  the updated [[moira.context|context]]."

  [txs]

  {:name ::execute-txs
   :enter (fn [ctx]
            (p/loop [{::keys [modules] :as ctx}
                     (update ctx ::txs context/into-queue txs)]
              (if (empty? modules)
                ctx
                (p/recur (execute-txs-1 ctx)))))})

(def ^:private n (namespace ::_))

(defn execute
  "Wrap `app` with a [[moira.context|context]] for execution and apply the
  transition `txs` on each module respectively. Returns a `Promise` that
  resolves to the updated `system-map`."

  [app txs]

  (p/-> {::app app}
        (context/execute n txs)
        (get ::app)))

(defn up
  "Elevate modules defined for `ks` and all their dependencies by applying the
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
  "Degrade modules defined for `ks` and all their dependencies by applying the
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
  "Update modules defined for `ks` by applying the interceptor chain `txs` in
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

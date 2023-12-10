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
  "Returns an [[moira.context|interceptor]] that inserts `ks` into `::modules`
  to schedule them as targets for a transition. The order of execution is
  determined by the dependency graph, so each module is guaranteed to be
  inserted after its dependencies.

  If the `include-deps?` option is enabled, the queue will include missing
  dependencies. The `reverse?` option inverts execution order."

  [ks & {:keys [include-deps? reverse?]}]

  {:name ::enqueue-modules
   :enter (fn [{::keys [app] :as ctx}]
            (let [ks* (resolve-module-ks app ks)
                  emit (fn [deps]
                         (cond->> deps
                           (not include-deps?) (filter (set ks*))
                           reverse? reverse))]
              (->> ks*
                   (module/dependency-chain app)
                   emit
                   (update ctx ::modules context/into-queue))))})

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
  interceptor chain `txs` on each module respectively. Returns a `Promise` that
  resolves to the updated `system-map`."

  [app txs]

  (p/-> {::app app}
        (context/execute n txs)
        (get ::app)))

(defn up
  "Elevate modules defined for `ks` and all their dependencies by applying the
  interceptor chain `txs` in the context of each module respectively. Returns a
  new application with updated module states.

  Dependencies are updated first, so each module's transition is guaranteed to
  be applied before any depending modules. `:app-log` is injected, if not
  already present, and automatically added to each module's `:deps`. When a
  circular dependency is detected, an error is thrown, and no updates are
  applied."

  [app txs ks]

  {:pre [(s/valid? ::module/system-map app)
         (s/valid? ::context/txs txs)
         (s/valid? ::module-ks ks)]}

  (execute app [log.txs/inject
                log.txs/pause
                (enqueue-modules ks :include-deps? true)
                (execute-txs txs)]))

(defn down
  "Degrade modules defined for `ks` by applying the interceptor chain `txs` in
  the context of each module respectively. Returns a new application with
  updated module states.

  No additional dependencies are added, and order of execution is reversed.
  Consequently, each module's transition is guaranteed to be applied *after*
  any depending modules. `:app-log` is injected, if not already present, and
  automatically added to each module's `:deps`. When a circular dependency is
  detected, an error is thrown, and no updates are applied."

  [app txs ks]

  {:pre [(s/valid? ::module/system-map app)
         (s/valid? ::context/txs txs)
         (s/valid? ::module-ks ks)]}

  (execute app [log.txs/inject
                log.txs/pause
                (enqueue-modules ks :reverse? true)
                (execute-txs txs)]))

(defn tx
  "Update modules defined for `ks` by applying the interceptor chain `txs` in
  the context of each module respectively. Returns a new application with
  updated module states.

  No additional dependencies are added, but order of execution is preserved.
  Consequently, each module's transition is guaranteed to be applied *before*
  any depending modules. `:app-log` is injected, if not already present, and
  automatically added to each module's `:deps`. When a circular dependency is
  detected, an error is thrown, and no updates are applied."

  [app txs ks]

  {:pre [(s/valid? ::module/system-map app)
         (s/valid? ::context/txs txs)
         (s/valid? ::module-ks ks)]}

  (execute app [log.txs/inject
                log.txs/pause
                (enqueue-modules ks)
                (execute-txs txs)]))

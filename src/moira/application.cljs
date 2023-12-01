(ns moira.application
  "Instrument modules and their dependencies by wrapping `system-map` with an
  instance of [[Application]].

  When system lifecycle events such as [[start!]], [[stop!]], [[pause!]], or
  [[resume!]] are triggered, the corresponding state
  [[moira.transition|transition]] executes on each module and its dependencies
  in order.

      (def system {:module-a {:start #'module-a/start}
                   :module-b {:deps #{:module-a}
                              :start #'module-b/start}})

      (defonce app (application/create system))

      (defn ^:export init []
        (application/start! app))

  All modules can leverage the unified log for inter-module communication via
  Application Events. The `:app-log` module is automatically injected into the
  system, with all other modules implicitly depending on it for access to the
  Application Log API.

      (defn start [_ {{:keys [on]} :app-log}]
        (on :something-happened #(pr \"Something happened:\" %)))
  "

  (:require [clojure.spec.alpha :as s]
            [moira.module :as module]
            [moira.transition :as transition]
            [promesa.core :as p]))

(defprotocol Thenable
  "Access the settled state of an [[Application]] instance."

  (then [this f]
    "`f` is called asynchronously with a settled `system-map` after all
    scheduled transitions finish.

    Returns a `Promise` that resolves to the updated `system-map`."))

(defprotocol Chainable
  "Schedule state updates of an [[Application]] instance."

  (then! [this f]
    "Enqueue function `f` to update `this` after all currently scheduled
    transitions finish.

    Replaces internal application state with the return value (or resolved
    value if a `Promise`) of applying `f` on the settled `system-map`.

    Returns a `Promise` that resolves to the updated `system-map`."))

(defprotocol Transitionable
  "Manage the lifecycle of an [[Application]] through chained updates. The
  [[moira.transition]] is guaranteed to be applied on module dependencies first
  for [[up!]] and last for [[down!]]."

  (up! [this txs ks]
    "Update `this` by elevating modules defined for `ks` and all their
    dependencies in order.

    The interceptor chain `txs` is applied in context of each module
    respectively. When a circular dependency is detected, no updates are
    applied, and an error is thrown. Used internally by [[start!]] and
    [[resume!]]. See [[moira.transition/up|transition/up]] for more details.

    Returns a `Promise` that resolves to the updated `system-map`.")

  (down! [this txs ks]
    "Update `this` by tearing down modules defined for `ks` and all their
    dependencies in reverse order.

    The interceptor chain `txs` is applied in context of each module
    respectively. When a circular dependency is detected, no updates are
    applied, and an error is thrown. Used internally by [[stop!]] and
    [[pause!]]. See [[moira.transition/down|transition/down]] for more details.

    Returns a `Promise` that resolves to the updated `system-map`.")

  (tx! [this txs ks]
    "Update `this` by updating modules defined for `ks` without handling
    dependencies.

    The interceptor chain `txs` is applied in context of each module
    respectively. When a circular dependency is detected, no updates are
    applied, and an error is thrown. See [[moira.transition/tx|transition/tx]]
    for details.

    Returns a `Promise` that resolves to the updated `system-map`."))

(defprotocol Extendable
  "Enhance the functionality of [[Application]] by adding new modules or
  modifying existing ones."

  (extend! [this modules]
    "Extend `this` to include `modules` defined as a map of module keys to
    (potentially partial) module definitions. New values will be added, but
    existing values are not changed.

    The update will be applied once all currently ongoing transitions have
    finished.

    Returns a `Promise` that resolves to the updated `system-map`.")

  (override! [this modules]
    "Update `this` to include `modules` defined as a map of module keys to
    (potentially partial) module definitions. New values will be added, and
    existing values will be overridden.

    The update will be applied once all currently ongoing transitions have
    finished. New values will be added, and existing values will be overridden.

    Returns a `Promise` that resolves to the updated `system-map`."))

(def ^:dynamic *timeout*
  "Maximum duration in milliseconds for a [[moira.transition|transition]] to
  complete.

  Lifecycle events should settle swiftly to minimize the period during which
  the system is in a transitional state. If the update is not fulfilled or
  rejected within `*timeout*` milliseconds, it is dismissed with a
  `TimeoutException`. Additionally, this prevents the [[Application]] from
  getting stuck due to a `Promise` deadlock.

  Lowering this value and enabling warnings during development is recommended
  to identify problems early. See [[*warnings*]] for an example."

  500)

(def ^:dynamic *warnings?*
  "Enable warnings in the browser console.

  Warnings are turned off by default, and it is recommended to set the value to
  `true` during development.

      (when ^boolean js/goog.DEBUG ; development only
        (set! application/*warnings?* true)
        (set! application/*timeout* 100))
  "

  false)

(deftype
 ^{:doc
   "Wrap the `system-map` passed to [[create]] with an instance of
    `Application` to instrument modules, manage state, and execute transitions.

    `Application` implements
   [`IDeref`](https://cljs.github.io/api/cljs.core/IDeref),
   [`IWatchable`](https://cljs.github.io/api/cljs.core/IWatchable), and
   [[Thenable]] for inspecting internal system state.

   ```clojure
   ;; get current (potentially transient) system state
   @app ; => {:module-a ,,,}

   ;; call f with next settled system state
   (application/then app f) ; => #<Promise[~]>

   ;; watch app for state changes
   (add-watch app :debug #(pr %4))
   ```

   The implementation of [[Transitionable]] and [[Extendable]] relies on
   [[Chainable]] to execute transitions in a sequential order. The functions
   [[up!]], [[down!]], and [[tx!]] are primarily used internally by the default
   lifecycle commands including [[start!]], [[stop!]], [[pause!]], and
   [[resume!]].

   ```clojure
   (when ^boolean js/goog.DEBUG ; development only
     (application/override! app dev-overrides))

     (defn ^:dev/before-load pause []
       (application/pause! app))

     (defn ^:dev/after-load resume []
       (application/resume! app))
   ```

   However, if you want to create your own custom lifecycle commands, you can
   use these functions directly.

   ```clojure
   (defn reset! [app]
     (application/up! app [(module/step :reset)] :all))
   ```
   "}

 Application

 [!state !tail]

  IDeref
  (-deref [_] @!state)

  IWatchable
  (-notify-watches [_ oldval newval]
    (-notify-watches !state oldval newval))
  (-add-watch [_ key f]
    (-add-watch !state key f))
  (-remove-watch [_ key]
    (-remove-watch !state key))

  Thenable
  (then [_ f]
    (p/then @!tail f))

  Chainable
  (then! [_ f]
    (let [t *timeout*
          warnings? *warnings?*
          tail @!tail]
      (vswap! !tail #(-> %
                         (p/then f)
                         (p/timeout t)
                         (p/then' (partial reset! !state))
                         (p/catch
                          (fn [ex]
                            (vreset! !tail tail)
                            (when warnings?
                              (.warn js/console
                                     "Moira system state restored after error:"
                                     (if (instance? p/TimeoutException ex)
                                       (str "Transition timed out. (" t " ms)")
                                       ex)))
                            (throw ex)))))))

  Transitionable
  (up! [_ txs ks]
    (then! _ #(transition/up % txs ks)))
  (down! [_ txs ks]
    (then! _ #(transition/down % txs ks)))
  (tx! [_ txs ks]
    (then! _ #(transition/tx % txs ks)))

  Extendable
  (extend! [_ modules]
    modules
    (then! _ #(merge-with merge modules %)))
  (override! [_ modules]
    (then! _ #(merge-with merge % modules))))

(s/def ::application (partial instance? Application))

(defn create
  "Create an Application instance from module definitions in `system-map`.

  Each entry in `system-map` defines a single module by mapping keywords to
  the respective module-maps incorporating all configuration and state."

  [system-map]

  {:pre [(s/valid? ::module/system-map system-map)]}

  (->Application (atom system-map) (volatile! (p/resolved system-map))))

(defn start!
  "Start modules defined in `ks` and all their dependencies. Start all modules
  defined by `app`, when no `ks` are given. Returns a promise resolving to the
  new system map with updated module states.

  Dependencies are guaranteed to be started first. `:app-log` is injected, if
  not already present, and automatically added to each module's `:deps`.

  Starting a module is idempotent (i.e., `:start` is only called, when the
  module is not already tagged as `:started`). Module `:state` is updated by
  calling `:start` on the current value and a map of dependency keys to values
  returned by applying respective `:export` on dependency `:state`.
  For additional context, the module key and the `app` instance are also passed
  to the update function.

  When a circular dependency is detected, an error is thrown and starting is
  aborted before any calls to `:start`."

  ([app] (start! app :all))

  ([app ks]
   (up! app
        [(module/enter :started)
         (module/step :start app)]
        ks)))

(defn stop!
  "Stop modules defined in `ks` and all their dependents. Stop all modules
  defined by `app` when no `ks` are given. Returns a promise resolving to the
  new system map with updated module states.

  Dependent modules are guaranteed to be stopped first. `:stop` is only called,
  when the module is tagged as `:started`. Nothing is called when a circular
  dependency is detected.

  Module `:state` is updated by calling `:stop` on the current value. `:stop`
  defaults to setting the state to `nil`."

  ([app] (stop! app :all))

  ([app ks]
   (down! app
          [(module/exit :started)
           (module/step #(get % :stop (constantly nil)) app)]
          ks)))

(defn pause!
  "Halt application by applying `:pause`.

  This is an alternate version of `stop` presumably called during development,
  e.g. before hot reloading code."

  ([app] (pause! app :all))

  ([app ks]
   (down! app
          [(module/only :started)
           (module/enter :paused)
           (module/step :pause app)]
          ks)))

(defn resume!
  "Reactivate application by applying `:resume`.

  This is an alternate version of `start` presumably called during development,
  e.g. after hot reloading code."

  ([app] (resume! app :all))

  ([app ks]
   (up! app
        [(module/exit :paused)
         (module/step :resume app)]
        ks)))

(defn load!
  "Load modules defined in `modules` into `app`. Returns a promise resolving to
  the new system map after start.

  Updates `app` and starts all provided modules immediately. Existing modules
  are extended and only started when not already up."

  [app modules]

  {:pre [(s/valid? ::application app)
         (s/valid? ::module/system-map modules)]}

  (extend! app modules)
  (start! app (keys modules)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn init!
  "Load `config` into `app`. Returns a promise resolving to
  the new system map after start."

  ([app config]

   {:pre [(s/valid? ::application app)
          (s/valid? ::module/system-map config)]}

   (override! app config)
   (start! app))

  ([app config ks]

   {:pre [(s/valid? ::application app)
          (s/valid? ::module/system-map config)]}

   (override! app config)
   (start! app ks)))

(comment
  (let [app (create {:mod-a {:state []
                             :start #(conj % :start)
                             :stop #(conj % :stop)
                             :pause #(conj % :pause)
                             :resume #(conj % :resume)}})]
    (add-watch app :debug-app-tx #(tap> %4))
    (do (start! app)
        (stop! app [:mod-a])
        (start! app [:mod-a])
        (pause! app [:mod-a])
        (resume! app [:mod-a])))

  IDeref)

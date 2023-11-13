(ns moira.application
  "Configure module dependencies, manage module lifecycles, and set up
  the unified application log for inter-module communication."

  (:require [clojure.spec.alpha :as s]
            [moira.module :as module]
            [moira.transition :as transition]
            [promesa.core :as p]))

(defprotocol Thenable
  (then [this f]
    "Return promise resolving to application state after current chain of
    commands has finished."))

(defprotocol Chainable
  (then! [this f]
    "Schedule state update `f` for after the current chain of commands has
    finished.

    Updates internal state from return value. Return value can be a promise
    resolving to the new state."))

(defprotocol Transitionable
  (up! [this txs ks]
    "Elevate modules `ks` and their dependencies by applying interceptors
    `txs`.")
  (down! [this txs ks]
    "Tear down modules `ks` and their dependencies in reversed order by
    applying interceptors `txs`.")
  (tx! [this txs ks]
    "Apply interceptors `txs` to modules `ks`."))

(defprotocol Extendable
  (extend! [this modules]
    "Extend `system-map` to include modules defined in `modules`.

    New properties are added and existing properties are not changed.")
  (override! [this modules]
    "Override `system-map` to update modules defined in `modules`.

    New properties are added and existing properties are changed."))

(def ^:dynamic *timeout* 500)
(def ^:dynamic *warnings?* false)

(deftype Application [!state !tail]
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
        (resume! app [:mod-a]))))

(ns moira.module
  "Apply the [[moira.transition|transition]] defined on the given
  [[moira.context|context]] in the scope of each individual module
  sequentially, following the order of the dependency graph.

  Inside this namespace, `app` refers to the immutable `system-map` directly
  and *not* to an instance of [[moira.application/Application|Application]]."

  (:require [clojure.spec.alpha :as s]
            [moira.context :as context]
            [moira.transition :as-alias transition]
            [promesa.core :as p]))

(s/def ::deps coll?)
(s/def ::export ifn?)
(s/def ::pause ifn?)
(s/def ::resume ifn?)
(s/def ::start ifn?)
(s/def ::state any?)
(s/def ::stop ifn?)
(s/def ::tags set?)
(s/def ::module
  (s/keys :opt-un [::deps
                   ::export
                   ::start
                   ::state
                   ::stop
                   ::pause
                   ::resume
                   ::tags]))
(s/def ::system-map
  (s/map-of any? ::module))

(defn ex-cyclic-deps
  "Returns an error with specific type and data for when a cyclic dependency
  was detected."

  [k visited]

  (ex-info "Aborted due to cyclic dependency!"
           {:type ::cycle-detected
            :target k
            :cycle visited}))

(defn ex-cyclic-deps?
  "Test helper for checking on error created by [[ex-cyclic-deps]]."

  [e]

  (= ::cycle-detected (:type (ex-data e))))

(defn postwalk-deps
  "Get the chain of dependencies for module `k`, depth-first and post-order.

  `app` must be a `system-map` including all relevant module definitions.
  Aborts by throwing [[ex-cyclic-deps]] when a circular dependency is
  detected."

  [app k]

  (let [walk (fn walk [k visited]
               (if (contains? visited k)
                 (throw (ex-cyclic-deps k visited))
                 (conj (into []
                             (mapcat #(walk % (conj visited k)))
                             (get-in app [k :deps]))
                       k)))]
    (walk k #{})))

(defn dependency-chain
  "Returns a lazy sequence of modules `ks` and all their dependencies, without
  duplicates and in the order of the dependency graph.

  `app` must be a `system-map` including all relevant module definitions.
  Aborts by throwing [[ex-cyclic-deps]] when a circular dependency is
  detected."

  [app ks]

  (sequence (comp (mapcat (partial postwalk-deps app)) (distinct)) ks))

(def ^:private n
  "Corresponding interceptors operate on a qualified `::queue` and `::stack`
  scoped to this namespace."

  (namespace ::_))

(defn execute
  "Update `ctx` by applying the interceptor chain `txs` to module `k`."

  [ctx k txs]

  (-> ctx
      (assoc ::current k)
      (context/execute n txs)))

(defn terminate
  "Terminate [[execute|execution]] for `::current` module. Returns an updated
  `ctx`."

  [ctx]

  (context/terminate ctx n))

(defn with-plugins

  "Update module definition by extending it with `:plugins`.

  A plugin definition maps keys to functions that extend the original behavior
  by invoking the function on the module definition's current value.

      (let [{:keys [foo]} (with-plugins
                            {:foo #(vector \"foo\" %)
                             :plugins [{:foo (fn [f]
                                               #(conj (f %) \"baz\"))}]})]
        (foo \"bar\")) ; => [\"foo\" \"bar\" \"baz\"]
  "

  [{:keys [plugins] :as module}]

  (let [ks (keys (apply merge module plugins))
        module* (into {} (map #(vector % (get module %))) ks)]
    (apply merge-with #(%2 %1) module* plugins)))

(defn- exports [ctx ks]
  (reduce (fn [m k]
            (let [{:keys [export state]
                   :or {export (constantly nil)}}
                  (with-plugins (get-in ctx [::transition/app k]))]
              (assoc m k (export state))))
          {}
          ks))

(defn step
  "Returns an interceptor that will update the current module's state by
  applying the function returned by calling `f` on the module. The update
  function is passed the current state of the module, exports from its
  dependencies, its module key in the system map, and any additional
  arguments."
  [f & args]
  {:name ::step
   :enter (fn [{::keys [current] :as ctx}]
            (let [{:keys [deps state] :as module}
                  (get-in ctx [::transition/app current])]
              (if-let [update-state (f (with-plugins module))]
                (p/let [state* (apply update-state
                                      state
                                      (exports ctx deps)
                                      current
                                      args)]
                  (assoc-in ctx [::transition/app current :state] state*))
                ctx)))})

(defn enter
  "Returns an interceptor that terminates execution if `k` is already present
  in `:tags`. Updates `:tags` to contain `k` on leave."
  [k]
  {:name ::enter
   :enter (fn [{::keys [current] :as ctx}]
            (let [{:keys [tags]} (get-in ctx [::transition/app current])]
              (cond-> ctx
                (contains? tags k) terminate)))
   :leave (fn [{::keys [current] :as ctx}]
            (update-in ctx
                       [::transition/app current :tags]
                       (comp set conj)
                       k))})

(defn- terminate-when-not-tagged-as [{::keys [current] :as ctx} k]
  (let [{:keys [tags]} (get-in ctx [::transition/app current])]
    (cond-> ctx
      (not (contains? tags k)) terminate)))

(defn exit
  "Returns an interceptor that terminates execution if `k` is not present in
  `:tags`. Updates `:tags` to no longer contain `k` on leave."
  [k]
  {:name ::exit
   :enter #(terminate-when-not-tagged-as % k)
   :leave (fn [{::keys [current] :as ctx}]
            (update-in ctx
                       [::transition/app current :tags]
                       disj
                       k))})

(defn only
  "Returns an interceptor that terminates execution if `k` is not present in
  `:tags`."
  [k]
  {:name ::only
   :enter #(terminate-when-not-tagged-as % k)})

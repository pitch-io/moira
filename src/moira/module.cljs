(ns moira.module
  (:require [clojure.spec.alpha :as s]
            [moira.context :as context]
            [promesa.core :as p]))

(s/def ::deps coll?)
(s/def ::export ifn?)
(s/def ::pause ifn?)
(s/def ::resume ifn?)
(s/def ::start ifn?)
(s/def ::state any?)
(s/def ::stop ifn?)
(s/def ::tags (s/nilable set?)) ;; gets set by the lifecycle fns, not in the initial, user-provided system-map
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

(defn- ex-cyclic-deps [k visited]
  (ex-info "Start aborted due to cyclic dependency"
           {:type ::cycle-detected
            :target k
            :cycle visited}))

(defn ex-cyclic-deps? [e]
  (= ::cycle-detected (:type (ex-data e))))

(defn postwalk-deps
  "Get the chain of dependencies depth-first and post-order.

  Aborts by throwing `ex-cyclic-deps` when a circular dependency is detected."
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
  "Returns deduped list of dependencies for module `ks`."
  [app ks]
  (sequence (comp (mapcat (partial postwalk-deps app)) (distinct)) ks))

(def ^:private n (namespace ::_))

(defn execute
  "Execute interceptors `txs` with current module `k` on transition `ctx`."
  [ctx k txs]
  (-> ctx
      (assoc ::current k)
      (context/execute n txs)))

(defn terminate
  "Terminate execution of interceptor chain for current module."
  [ctx]
  (context/terminate ctx n))

(defn with-plugins
  "Extend the module definition by applying `:plugins`."
  [{:keys [plugins] :as module}]
  (->> plugins
       (map #(select-keys % (keys module)))
       (apply merge-with #(%2 %1) module)))

(defn- exports [ctx ks]
  (reduce (fn [m k]
            (let [{:keys [export state]
                   :or {export (constantly nil)}}
                  (with-plugins (get-in ctx [:moira.transition/app k]))]
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
                  (get-in ctx [:moira.transition/app current])]
              (if-let [update-state (f (with-plugins module))]
                (p/let [state* (apply update-state
                                      state
                                      (exports ctx deps)
                                      current
                                      args)]
                  (assoc-in ctx [:moira.transition/app current :state] state*))
                ctx)))})

(defn enter
  "Returns an interceptor that terminates execution if `k` is already present
  in `:tags`. Updates `:tags` to contain `k` on leave."
  [k]
  {:name ::enter
   :enter (fn [{::keys [current] :as ctx}]
            (let [{:keys [tags]} (get-in ctx [:moira.transition/app current])]
              (cond-> ctx
                (contains? tags k) terminate)))
   :leave (fn [{::keys [current] :as ctx}]
            (update-in ctx
                       [:moira.transition/app current :tags]
                       (comp set conj)
                       k))})

(defn- terminate-when-not-tagged-as [{::keys [current] :as ctx} k]
  (let [{:keys [tags]} (get-in ctx [:moira.transition/app current])]
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
                       [:moira.transition/app current :tags]
                       disj
                       k))})

(defn only
  "Returns an interceptor that terminates execution if `k` is not present in
  `:tags`."
  [k]
  {:name ::only
   :enter #(terminate-when-not-tagged-as % k)})

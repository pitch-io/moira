(ns moira.context
  "Implementation of the interceptor pattern based on an asynchronous promise
  chain.

  Interceptors map hook functions to the three execution stages `:enter`,
  `:leave`, and `:error`. All hook functions are optional, will be passed
  the current execution context, and need to return an updated context.

  When executing a context, first each `:enter` hook is triggered in order.
  After that, each intereptor's `:leave` hook will be invoked in reverse order.
  At any point, when an error occurs the execution of remaining interceptors is
  terminated and the `:error` function of previously processed interceptors is
  triggered in reverse order.

  The `::queue` of pending interceptors and `::stack` of partially processed
  inteceptors are always present on the context. It is perfectly valid for
  interceptor hooks to alter those in order to change execution.

  The current error is available via `::error`. To catch an error return a
  context with the key removed. To signal an error return a context with the
  `::error` key present."
  (:require [clojure.spec.alpha :as s]
            [promesa.core :as p]))

(s/def ::enter fn?)
(s/def ::error (partial instance? js/Error))
(s/def ::leave fn?)
(s/def ::tx (s/keys :opt-un [::enter ::error ::leave]))
(s/def ::txs (s/coll-of ::tx))

(defn done?
  "Returns true, if there are no more pending execution steps."
  [ctx n]
  (and (empty? (get ctx (keyword n :queue)))
       (empty? (get ctx (keyword n :stack)))))

(defn error?
  "Returns true, if execution is in error stage."
  [ctx n]
  (contains? ctx (keyword n :error)))

(defn queue? [v]
  (instance? PersistentQueue v))

(defn into-queue [coll xs]
  (let [queue (if (queue? coll) coll (into #queue [] coll))]
    (into queue xs)))

(defn enqueue
  "Add interceptors to the end of the execution queue."
  [ctx n txs]
  (update ctx (keyword n :queue) into-queue txs))

(defn terminate
  "Drop execution of any pending interceptors and immediately switch to the
  leave stage."
  [ctx n]
  (assoc ctx (keyword n :queue) #queue []))

(defn into-stack [coll xs]
  (apply conj coll xs))

(defn stack
  "Add inteceptors to the top of the execution stack."
  [ctx n txs]
  (update ctx (keyword n :stack) into-stack txs))

(defn ->ex [v data]
  (ex-info
   (or (ex-message v) (str v))
   (assoc data ::ex v)))

(defn- step [ctx n f g data]
  (-> (p/resolved ctx)
      (p/then (if (error? ctx n) g f))
      (p/catch #(assoc ctx (keyword n :error) (->ex % data)))))

(defn enter-1
  "Execute next enter step.

  Returns a promise resolving to the updated context. Fails gracefully, if not
  applicable."
  [ctx n]
  (let [q (get ctx (keyword n :queue))]
    (if (seq q)
      (let [{:keys [enter name] :or {enter identity} :as tx} (peek q)]
        (-> ctx
            (assoc (keyword n :queue) (pop q))
            (update (keyword n :stack) conj tx)
            (step n enter identity {::name name ::stage :enter})))
      (p/resolved ctx))))

(defn leave-1
  "Execute next leave or error step.

  Returns a promise resolving to the updated context. Fails gracefully, if not
  applicable."
  [ctx n]
  (let [s (get ctx (keyword n :stack))]
    (if (seq s)
      (let [{:keys [error leave name]
             :or {error identity leave identity}} (peek s)]
        (-> ctx
            (assoc (keyword n :stack) (pop s))
            (step n leave error {::name name
                                 ::stage (if (error? ctx n) :error :leave)})))
      (p/resolved ctx))))

(defn execute-1
  "Execute next enter, leave, or error step.

  Returns a promise resolving to the updated context. Fails gracefully, if not
  applicable."
  [ctx n]
  (cond
    (seq (get ctx (keyword n :queue))) (enter-1 ctx n)
    (seq (get ctx (keyword n :stack))) (leave-1 ctx n)
    :else (p/resolved ctx)))

(defn emit [ctx n]
  (if (error? ctx n) (p/rejected (get ctx (keyword n :error))) ctx))

(defn execute-all
  "Execute all enter, leave, or error steps.

  Returns a promise resolving to the updated context. Fails gracefully, if not
  applicable."
  [ctx n]
  (p/loop [ctx ctx]
    (if (done? ctx n)
      ctx
      (p/recur (execute-1 ctx n)))))

(defn execute
  "Apply a chain of interceptors `txs` to context `ctx`.

  `ctx` can be a plain map and will be elevated to be an executable context by
  enqueueing `txs`. Returns a promise resolving to the updated context."
  [ctx n txs]
  (p/-> ctx
        (enqueue n txs)
        (execute-all n)
        (emit n)))

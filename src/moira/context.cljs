(ns moira.context
  "Implementation of the [interceptor design
  pattern](https://lambdaisland.com/episodes/interceptors-concepts) based on
  asynchronous `Promise` chains.

  The context map expresses the current state of execution in plain data,
  including error handling and support for asynchronous execution. `ctx` is
  updated in sequence by taking interceptors from `::queue` and passing them
  onto `::stack` while applying the `:enter` function of each interceptor. Once
  the `::queue` is empty, interceptors are popped from `::stack`, which causes
  each interceptor's `:leave` function to be called in reverse order. At any
  point, when an exception is thrown, it is caught, all queued interceptors are
  terminated, and `::error` is associated with the context. The stack will
  continue to handle active interceptors by calling their respective `:error`
  function for clean-up.

  An interceptor chain, called `txs`, is a sequential collection of
  interceptors to be executed in order. Each `tx` connects to one or more
  execution stages by mapping functions to `:enter`, `:leave`, and `:error`
  respectively. Any of those functions will be called on the current execution
  context and must return an updated `ctx` or a `Promise` resolving to the new
  context. Note that interceptor functions are perfectly valid to adjust
  `::queue` or `::stack` to alter execution. Similarly, returning a new context
  with the `::error` key removed will settle an error. To signal an error,
  return a context with the exception attached to `::error`.

   Moira supports nesting of execution by operating on multiple qualified
  `::queue`, `::stack`, and `::error` keys. To enable this, relevant functions
  require a prefix `n` as second argument. By convention, `n` should be the
  name of the corresponding namespace. This feature enables independent
  execution while providing access to a shared context. Setting up a
  [[moira.transition|transition]] through interceptors is clearly separated
  from executing `::txs` on each [[moira.module|module]] individually."

  (:require [clojure.spec.alpha :as s]
            [promesa.core :as p]))

(s/def ::enter fn?)
(s/def ::error fn?)
(s/def ::leave fn?)
(s/def ::tx (s/keys :opt-un [::enter ::error ::leave]))
(s/def ::txs (s/coll-of ::tx))

(defn done?
  "Returns true if there are no more pending execution steps for `ctx` within
  the scope of `n`.

  `n` is used to qualify respective `:queue`, `:stack`, and `:error` keys."

  [ctx n]

  (and (empty? (get ctx (keyword n :queue)))
       (empty? (get ctx (keyword n :stack)))))

(defn error?
  "Returns true, if scope `n` of `ctx` is in `:error` stage.

  `n` is used to qualify respective `:queue`, `:stack`, and `:error` keys."

  [ctx n]

  (contains? ctx (keyword n :error)))

(defn queue?
  "Returns true if `x` is an instance of `PersistentQueue`."

  [x]

  (instance? PersistentQueue x))

(defn into-queue
  "Returns a `PersistentQueue` from `coll` with all `xs` conjoined."

  [coll xs]

  (let [queue (if (queue? coll) coll (into #queue [] coll))]
    (into queue xs)))

(defn enqueue
  "Schedule interceptors `txs` for execution within scope `n`. Returns an
  updated `ctx`.

  `n` is used to qualify respective `:queue`, `:stack`, and `:error` keys."

  [ctx n txs]

  {:pre [(s/valid? ::txs txs)]}

  (update ctx (keyword n :queue) into-queue txs))

(defn terminate
  "Drop execution of all pending interceptors within scope `n` and immediately
  switch to exit stage (i.e., `:leave` or `:error`). Returns an updated `ctx`.

  `n` is used to qualify respective `:queue`, `:stack`, and `:error` keys."

  [ctx n]

  (assoc ctx (keyword n :queue) #queue []))

(defn into-stack [coll xs]
  (apply conj coll xs))

(defn stack
  "Add inteceptors `txs` to the top of the execution stack for scope `n`.
  Returns an updated `ctx`.

  `n` is used to qualify respective `:queue`, `:stack`, and `:error` keys."

  [ctx n txs]

  {:pre [(s/valid? ::txs txs)]}

  (update ctx (keyword n :stack) into-stack txs))

(defn ->ex
  "Wrap `x` into an instance of `ExceptionInfo` with `data`."

  [x data]

  (ex-info
   (or (ex-message x) (str x))
   (assoc data ::ex x)))

(defn- step [ctx n f g data]
  (-> (p/resolved ctx)
      (p/then (if (error? ctx n) g f))
      (p/catch #(assoc ctx (keyword n :error) (->ex % data)))))

(defn enter-1
  "Execute next `:enter` step for scope `n`. Returns a `Promise` resolving to
  the updated `ctx`.

  Fails gracefully, if not applicable. `n` is used to qualify respective
  `:queue`, `:stack`, and `:error` keys."

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
  "Execute next `:leave` or `:error` step for scope `n`. Returns a `Promise`
  resolving to the updated `ctx`.

  Fails gracefully, if not applicable. `n` is used to qualify respective
  `:queue`, `:stack`, and `:error` keys."

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
  "Execute next `:enter`, `:leave`, or `:error` step for scope `n`. Returns a
  `Promise` resolving to the updated `ctx`.

  Fails gracefully, if not applicable. `n` is used to qualify respective
  `:queue`, `:stack`, and `:error` keys."

  [ctx n]

  (cond
    (seq (get ctx (keyword n :queue))) (enter-1 ctx n)
    (seq (get ctx (keyword n :stack))) (leave-1 ctx n)
    :else (p/resolved ctx)))

(defn execute-all
  "Execute all `:enter`, `:leave`, or `:error` steps for scope `n`. Returns a
  `Promise` resolving to the updated `ctx`.

  Fails gracefully, if not applicable. `n` is used to qualify respective
  `:queue`, `:stack`, and `:error` keys."

  [ctx n]

  (p/loop [ctx ctx]
    (if (done? ctx n)
      ctx
      (p/recur (execute-1 ctx n)))))

(defn- emit [ctx n]
  (if (error? ctx n) (p/rejected (get ctx (keyword n :error))) ctx))

(defn execute
  "Apply a chain of interceptors `txs` to `ctx` within scope `n`. Returns a
  `Promise` resolving to the updated context.

  `ctx` can be a plain map and will be elevated to be an executable context by
  enqueueing `txs`. `n` is used to qualify respective `:queue`, `:stack`, and
  `:error` keys."

  [ctx n txs]

  {:pre [(s/valid? ::txs txs)]}

  (p/-> ctx
        (enqueue n txs)
        (execute-all n)
        (emit n)))

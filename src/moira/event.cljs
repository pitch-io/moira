(ns moira.event
  "Implement [[Factory]] for producing Application Events with unique
  [[EventId|Event IDs]] and guaranteed ordering based on a
  [logical clock](https://en.wikipedia.org/wiki/Logical_clock)."

  (:require [clojure.spec.alpha :as s]
            [goog.array :as garray]))

(defprotocol Dateable
  "Decode timestamp from [[EventId]]."

  (date-prefix [this]
    "Extract part of the [[EventId]] representing the timestamp.")
  (date [this]
    "Get `Date` corresponding to [[EventId]]."))

(defprotocol Attributable
  "Decode client from [[EventId]]."

  (app [this]
    "Extract part of the [[EventId]] representing the local application."))

(defprotocol Countable
  "Decode counter from [[EventId]]."

  (counter [this]
    "Extract part of the [[EventId]] representing the counter.

    The counter ensures that events with identical [[date-prefix|timestamp]]
    and [[app|application id]] are always unique and in order."))

(deftype
 ^{:doc
   "Uniquely identify any Application Event by encoding a
   [[timestamp|Dateable]], [[client|Attributable]], and
   [[counter|Countable]].

   Supports type-and-value based equality through `=`. Relative order can be
   determined with `<`, `>`, `sort`, etc.

   Tagged literal support is added for both reading and writing.

       #event/id \"ktpgcdxgxhmkwoookboe00\"
   "}

 EventId

 [id ^:mutable __hash]

  Object
  (toString [_] id)
  (equiv [this other]
    (-equiv this other))

  IEquiv
  (-equiv [_ other]
    (and (instance? EventId other)
         (identical? id (.-id other))))

  IPrintWithWriter
  (-pr-writer [_ writer _]
    (-write writer (str "#event/id \"" id "\"")))

  IHash
  (-hash [_]
    (when (nil? __hash)
      (set! __hash (hash id)))
    __hash)

  IComparable
  (-compare [this other]
    (if (instance? EventId other)
      (garray/defaultCompare id (.-id other))
      (throw (js/Error. (str "Cannot compare " this " to " other)))))

  Dateable
  (date-prefix [_]
    (.substr id 0 8))
  (date [this]
    (-> (date-prefix this)
        (js/parseInt 36)
        (js/Date.)))

  Attributable
  (app [_]
    (.substr id 8 12))

  Countable
  (counter [_]
    (.substring id 20)))

(def event-id? (partial instance? EventId))
(s/def ::date (partial instance? js/Date))
(s/def ::id event-id?)
(s/def ::type keyword?)
(s/def ::data any?)

(s/def ::event (s/keys :req-un [::date ::id ::type]
                       :opt-un [::data]))

(s/def ::now pos-int?)
(s/def ::date-prefix (s/and string? (partial re-matches #"[0-9a-z]{8}")))

(defn string->event-id
  "Make [[EventId]] from `s`.

  `s` needs to be a string concatenatinge [[date-prefix]], [[app|client
  identifyer]], and [[counter]]."

  [s]

  (->EventId (.toLowerCase s) nil))

(defn counter->suffix
  "Encode `x` into a string suitable for generating [[EventId]]."

  [x]

  (-> x (.toString 36) (.padStart 4 "0")))

(defn date->prefix
  "Encode `date` into a string suitable for generating [[EventId]]."

  [ms]

  {:pre [(s/valid? ::now ms)]
   :post [(s/valid? ::date-prefix %)]}

  (-> ms (.toString 36) (.padStart 8 "0")))

(defn monotonic-now
  "Get milliseconds elapsed since
  [epoch](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Date#the_epoch_timestamps_and_invalid_date)
  from the
  [monotonic clock](https://w3c.github.io/hr-time/#dfn-monotonic-clock.

  Timestamp to be used for generating [[Eventid]]."

  []

  {:post [(s/valid? ::now %)]}

  (-> (js/performance.now)
      (+ js/performance.timeOrigin)
      js/Math.floor))

(defn current-date
  "Get `Date` from [wall clock](https://w3c.github.io/hr-time/#ref-for-dfn-wall-clock-1).

  Timestamp to be used as default `:date` value by [[EventFactory]]."

  []

  {:post [(s/valid? ::date %)]}

  (js/Date.))

(defn- rand-gen []
  (repeatedly #(.toString (rand-int 36) 36)))

(defn- rand-id [length]
  (->> (rand-gen) (take length) (apply str)))

(defn- app-id []
  (rand-id 12))

(defprotocol Counter
  "Produce an incremental stream of counter values.

  Ensures a deterministic order of [[EventId|Event IDs]] for [[app]] with the
  same [[date|timestamps]]."

  (next-count [this]
    "Get tuple of current [[monotonic-now|timestamp]] and next [[counter]]
    value."))

(defprotocol IdGenerator
  "Produce a stream of unique [[EventId|Event IDs]] in context of [[Factory]]."

  (next-id [this]
    "Get next [[EventId]]."))

(deftype
 ^{:doc
   "Manage state for creating unique [[EventId|Event IDs]] in context of the
   current [[app]]."}

 EventIdGenerator

 [now app-id counter]

  Counter
  (next-count [_]
    (let [t* (now)]
      (vswap! counter (fn [[t x]] [t* (if (= t* t) (inc x) 0)]))))

  IdGenerator
  (next-id [this]
    (let [[t x] (next-count this)]
      (string->event-id
       (str (date->prefix t) app-id (counter->suffix x))))))

(defprotocol Factory
  "Make Application Events using unique [[EventId|Event IDs]] with
  deterministic order based on a
  [logical clock](https://en.wikipedia.org/wiki/Logical_clock)."

  (->event [this m]
    "Make Application Event from `m` with `:id` generated by
    [[EventIdGenerator]].

    `:date` is automatically set from
    [wall clock](https://w3c.github.io/hr-time/#ref-for-dfn-wall-clock-1), if
    not already present."))

(deftype ^:no-doc EventFactory [current-date id-generator]
  Factory
  (->event [_ m]
    (when (contains? m :id)
      (throw (ex-info "Must not contain `:id`" m)))

    (-> m
        (select-keys #{:data :date :type})
        (assoc :id (next-id id-generator))
        (update :date #(or % (current-date))))))

(defn factory
  "Create a [[Factory]] by wrapping an instance of [[EventIdGenerator]] scoped
  to the current [[app]]."

  []

  (->> (volatile! nil)
       (->EventIdGenerator monotonic-now (app-id))
       (->EventFactory current-date)))

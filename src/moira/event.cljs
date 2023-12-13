(ns moira.event
  "Create Application Events using unique [[EventId|IDs]] with deterministic
  order based on a
  [logical clock](https://en.wikipedia.org/wiki/Logical_clock)."

  (:require [clojure.spec.alpha :as s]
            [goog.array :as garray]))

(defprotocol ^:no-doc Dateable
  (date-prefix [this])
  (date [this]))

(defprotocol ^:no-doc Attributable
  (app [this]))

(defprotocol ^:no-doc Countable
  (counter-suffix [this])
  (counter-value [this]))

(defprotocol ^:no-doc Counter
  (next-count [this]))

(deftype ^:no-doc EventId [id ^:mutable __hash]
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
  (counter-suffix [_]
    (.substring id 20))
  (counter-value [this]
    (-> (counter-suffix this)
        (js/parseInt 36))))

(def event-id? (partial instance? EventId))
(s/def ::date (partial instance? js/Date))
(s/def ::id event-id?)
(s/def ::type keyword?)
(s/def ::data any?)

(s/def ::event (s/keys :req-un [::date ::id ::type]
                       :opt-un [::data]))

(s/def ::now pos-int?)
(s/def ::date-prefix (s/and string? (partial re-matches #"[0-9a-z]{8}")))

(defn string->event-id [s]
  (->EventId (.toLowerCase s) nil))

(defn counter->suffix [x]
  (-> x (.toString 36) (.padStart 4 "0")))

(defn date->prefix
  "Encode `date` into format suitable for generating `EventId`."
  [ms]
  {:pre [(s/valid? ::now ms)]
   :post [(s/valid? ::date-prefix %)]}
  (-> ms (.toString 36) (.padStart 8 "0")))

(defn monotonic-now []
  {:post [(s/valid? ::now %)]}
  (-> (js/performance.now)
      (+ js/performance.timeOrigin)
      js/Math.floor))

(defn current-date []
  {:post [(s/valid? ::date %)]}
  (js/Date.))

(defn- rand-gen []
  (repeatedly #(.toString (rand-int 36) 36)))

(defn- rand-id [length]
  (->> (rand-gen) (take length) (apply str)))

(defn app-id []
  (rand-id 12))

(defprotocol IdGenerator
  (next-id [this] "Generate unique event-id in context of `this`."))

(deftype EventIdGenerator [now app-id counter]
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
  (->event [this m] "Create event from data `m` with generated `:id`."))

(deftype EventFactory [current-date id-generator]
  Factory
  (->event [_ m]
    (when (contains? m :id)
      (throw (ex-info "Must not contain `:id`" m)))

    (-> m
        (select-keys #{:data :date :type})
        (assoc :id (next-id id-generator))
        (update :date #(or % (current-date))))))

(defn factory []
  (->> (volatile! nil)
       (->EventIdGenerator monotonic-now (app-id))
       (->EventFactory current-date)))

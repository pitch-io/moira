(ns moira.log.event-emitter-test
  (:require [clojure.test :refer [deftest is testing]]
            [moira.event :as event]
            [moira.log.event-emitter :as event-emitter]))

(deftest enrich-event-test
  (let [f (reify event/Factory
            (->event [_ e] (assoc e :id "1234")))
        e (event-emitter/->EventEmitter f
                                        (volatile! [])
                                        (volatile! #queue [])
                                        (volatile! false))]
    (is (= {:type :test :id "1234"}
           (event-emitter/emit e {:type :test})))))

(deftest listen-to-emitted-events-test
  (let [!calls (atom [])
        track-call (fn [s] #(swap! !calls conj [s (select-keys % [:type])]))
        e (event-emitter/create)]
    (event-emitter/listen e (track-call "listener-1"))
    (event-emitter/listen e :event-a (track-call "listener-2"))
    (event-emitter/listen e :event-b (track-call "listener-3"))
    (event-emitter/listen e :event-b (track-call "listener-4"))
    (event-emitter/listen e (track-call "listener-5"))
    (testing "trigger registered callbacks in order"
      (event-emitter/emit e {:type :event-a})
      (event-emitter/emit e {:type :event-b})
      (event-emitter/emit e {:type :event-c})
      (is (= [["listener-1" {:type :event-a}]
              ["listener-2" {:type :event-a}]
              ["listener-5" {:type :event-a}]
              ["listener-1" {:type :event-b}]
              ["listener-3" {:type :event-b}]
              ["listener-4" {:type :event-b}]
              ["listener-5" {:type :event-b}]
              ["listener-1" {:type :event-c}]
              ["listener-5" {:type :event-c}]]
             @!calls)))))

(deftest stop-broadcasting-emitted-events
  (let [!calls (atom [])
        track-call (partial swap! !calls conj)
        e (event-emitter/create)]
    (event-emitter/listen e track-call)
    (event-emitter/emit e {:type :event-a})
    (testing "stop triggering callbacks"
      (event-emitter/unlisten e)
      (event-emitter/emit e {:type :event-b})
      (event-emitter/emit e {:type :event-c})
      (is (= [{:type :event-a}] (map #(select-keys % [:type]) @!calls))))))

(deftest stop-listening-to-emitted-events
  (let [!calls (atom [])
        track-call (fn [s] #(swap! !calls conj [s (select-keys % [:type])]))
        listener-1 (track-call "listener-1")
        listener-2 (track-call "listener-2")
        listener-3 (track-call "listener-3")
        e (event-emitter/create)]
    (event-emitter/listen e listener-1)
    (event-emitter/listen e listener-2)
    (event-emitter/listen e :event-a listener-3)
    (event-emitter/listen e :event-b listener-3)
    (event-emitter/listen e :event-c listener-3)
    (event-emitter/emit e {:type :event-a})
    (testing "stop triggering callbacks"
      (event-emitter/unlisten e listener-2)
      (event-emitter/unlisten e :event-b listener-3)
      (event-emitter/emit e {:type :event-b})
      (event-emitter/emit e {:type :event-c})
      (is (= [["listener-1" {:type :event-a}]
              ["listener-2" {:type :event-a}]
              ["listener-3" {:type :event-a}]
              ["listener-1" {:type :event-b}]
              ["listener-1" {:type :event-c}]
              ["listener-3" {:type :event-c}]]
             @!calls)))))

(deftest pause-and-resume-listening-to-events-test
  (let [!calls (atom [])
        listener #(swap! !calls conj %)
        e (event-emitter/create)]
    (event-emitter/listen e listener)
    (event-emitter/emit e {:type :event-a})
    (testing "pause triggering callbacks"
      (event-emitter/pause e)
      (event-emitter/emit e {:type :event-b})
      (event-emitter/emit e {:type :event-c})
      (is (= [{:type :event-a}]
             (map #(select-keys % [:type]) @!calls))))
    (testing "resume paused callbacks"
      (event-emitter/resume e)
      (is (= [{:type :event-a}
              {:type :event-b}
              {:type :event-c}]
             (map #(select-keys % [:type]) @!calls))))
    (testing "continue triggering callbacks"
      (event-emitter/emit e {:type :event-d})
      (is (= [{:type :event-a}
              {:type :event-b}
              {:type :event-c}
              {:type :event-d}]
             (map #(select-keys % [:type]) @!calls))))))

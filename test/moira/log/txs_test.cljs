(ns moira.log.txs-test
  (:require [clojure.test :refer [deftest is testing]]
            [moira.log.event-emitter :as event-emitter]
            [moira.log.module :as log.module]
            [moira.log.txs :as log.txs]
            [moira.module :as module]
            [moira.transition :as transition]))

(deftest ensure-dependency-test
  (let [{:keys [enter]} (log.txs/ensure-dependency :default-module)]
    (testing "add dependency to empty dependencies"
      (is (= #{:default-module}
             (-> {::transition/app {:module-a {}}
                  ::module/current :module-a}
                 enter
                 (get-in [::transition/app :module-a :deps])))))
    (testing "add dependency to existing dependencies"
      (is (= #{:default-module :module-b}
             (-> {::transition/app {:module-a {:deps #{:module-b}}}
                  ::module/current :module-a}
                 enter
                 (get-in [::transition/app :module-a :deps])))))
    (testing "do not add dependency to itself"
      (is (= #{:module-a}
             (-> {::transition/app {:default-module {:deps #{:module-a}}}
                  ::module/current :default-module}
                 enter
                 (get-in [::transition/app :default-module :deps])))))))

(deftest inject-test
  (with-redefs [log.txs/ensure-dependency (fn [k] {:name ::ensure-dependency
                                                   :key k})]
    (let [{:keys [enter]} log.txs/inject
          {::transition/keys [app modules txs]}
          (enter {::transition/app
                  {:app-log {:state "existing-app-log-state"}}})]
      (testing "updates app to include module"
        (let [app-log (:app-log app)]
          (is (= log.module/default
                 (select-keys app-log (keys log.module/default))))
          (is (= "existing-app-log-state" (:state app-log)))))
      (testing "enqueues module"
        (is (= [:app-log] modules)))
      (testing "esure dependency"
        (is (= [{:name ::ensure-dependency :key :app-log}] txs))))))

(deftest pause-test
  (let [!calls (atom nil)
        track-call (partial swap! !calls conj)
        track-transition (fn [ctx] (track-call "transition") ctx)
        clear-calls #(reset! !calls [])
        event-emitter (reify event-emitter/Resumable
                        (pause [_] (track-call "paused"))
                        (resume [_] (track-call "resumed")))
        {:keys [enter leave error]} log.txs/pause]
    (testing "wrap with pause and resume calls"
      (clear-calls)
      (-> {::transition/app {:app-log {:state {:event-emitter event-emitter}}}}
          enter
          track-transition
          leave)
      (is (= ["paused" "transition" "resumed"] @!calls)))
    (testing "resume on error"
      (clear-calls)
      (-> {::transition/app {:app-log {:state {:event-emitter event-emitter}}}}
          enter
          track-transition
          error)
      (is (= ["paused" "transition" "resumed"] @!calls)))
    (testing "fail gracefully when there is no app log present"
      (clear-calls)
      (-> {::transition/app {}}
          enter
          track-transition
          leave
          error)
      (is (= ["transition"] @!calls)))))

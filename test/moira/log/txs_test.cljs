(ns moira.log.txs-test
  (:require [clojure.test :refer [deftest is testing]]
            [moira.log.event-emitter :as event-emitter]
            [moira.log.module :as log.module]
            [moira.log.txs :as log.txs]
            [moira.transition :as transition]))

(deftest inject-test
  (let [{:keys [enter]} log.txs/inject
        {::transition/keys [app]}
        (enter {::transition/app {:module-a {}
                                  :module-b {:deps #{:module-a}}}})]
    (testing "updates app to include module"
      (is (= log.module/default (:app-log app)))
      (is (nil? (seq (get-in app [:app-log :deps])))))
    (testing "ensure dependency"
      (is (= #{:app-log} (get-in app [:module-a :deps])))
      (is (= #{:app-log :module-a} (get-in app [:module-b :deps]))))))

(deftest inject-into-exisiting-module-test
  (let [{:keys [enter]} log.txs/inject
        start* #(assoc % :foo "bar")
        {::transition/keys [app]}
        (enter {::transition/app {:app-log {:start start*}}})]
    (testing "does not override existing values"
      (is (= start* (get-in app [:app-log :start]))))))

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

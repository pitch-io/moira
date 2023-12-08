(ns moira.transition-test
  (:require [clojure.test :refer [are deftest is testing]]
            [moira.module :as module]
            [moira.test.async :refer [async-let]]
            [moira.test.util :as util]
            [moira.transition :as transition]
            [promesa.core :as p]))

(deftest enqueue-modules-test
  (let [{:keys [enter]} (transition/enqueue-modules [:module-c :module-d])]
    (testing "push modules onto queue"
      (are [before after] (= after (-> {::transition/modules before}
                                       enter
                                       (get ::transition/modules)
                                       util/peek-seq))
        nil [:module-c :module-d]
        #queue [:module-a :module-b] [:module-a :module-b :module-c :module-d]
        (list :module-a :module-b) [:module-a :module-b :module-c :module-d]
        [:module-a :module-b] [:module-a :module-b :module-c :module-d]))))

(deftest enqueue-module-with-deps-test
  (let [{:keys [enter]} (transition/enqueue-modules-with-deps [:module-c :module-d])]
    (testing "push modules onto queue"
      (are [before after] (= after (-> {::transition/modules before}
                                       enter
                                       (get ::transition/modules)
                                       util/peek-seq))
        nil [:module-c :module-d]
        #queue [:module-a :module-b] [:module-a :module-b :module-c :module-d]
        (list :module-a :module-b) [:module-a :module-b :module-c :module-d]
        [:module-a :module-b] [:module-a :module-b :module-c :module-d]))))

(deftest enqueue-dependency-tree-test
  (let [app {:module-a {:deps #{:module-c}}
             :module-b {:deps #{:module-a}}
             :module-c {}
             :module-d {}}
        {:keys [enter]} (transition/enqueue-modules-with-deps [:module-b])
        ctx {::transition/app app}]
    (testing "inject chain of dependencies in order"
      (is (= [:module-c :module-a :module-b]
             (-> (enter ctx)
                 (get ::transition/modules)
                 util/peek-seq))))))

(deftest reverse-modules-test
  (let [{:keys [enter]} transition/reverse-modules
        ctx {::transition/modules #queue [:module-a :module-b :module-c]}]
    (testing "reverse order of modules"
      (is (= [:module-c :module-b :module-a]
             (-> (enter ctx)
                 (get ::transition/modules)
                 util/peek-seq))))
    (testing "restore original order by reversing again"
      (is (= [:module-a :module-b :module-c]
             (-> (enter ctx)
                 (enter ctx)
                 (get ::transition/modules)
                 util/peek-seq))))))

(deftest execute-txs-test
  (let [tx {:enter #(update % ::calls conj (::module/current %))}
        {:keys [enter]} (transition/execute-txs [tx])
        ctx {::calls []
             ::transition/modules #queue [:module-a :module-b :module-c]}]
    (async-let [{::keys [calls]} (enter ctx)]
      (testing "execute each module in order"
        (is (= [:module-a :module-b :module-c] calls))))))

(deftest execute-txs-async-test
  (let [tx {:enter (fn [ctx]
                     (let [track-call #(update ctx ::calls conj %)]
                       (case (::module/current ctx)
                         :module-a (track-call :module-a)
                         :module-b (p/resolved (track-call :module-b))
                         :module-c (p/delay 10 (track-call :module-c)))))}
        {:keys [enter]} (transition/execute-txs [tx])
        ctx {::calls []
             ::transition/modules #queue [:module-a :module-b :module-c]}]
    (async-let [{::keys [calls]} (enter ctx)]
      (testing "execute each module in order"
        (is (= [:module-a :module-b :module-c] calls))))))

(deftest wrap-app-test
  (let [app {::my-module {:state "initial state"}}
        tx {:enter #(assoc-in %
                              [::transition/app ::my-module :state]
                              "tested")}]
    (async-let [{::keys [my-module]} (transition/execute app [tx])]
      (testing "resolve with updated app"
        (is (= "tested" (:state my-module)))))))

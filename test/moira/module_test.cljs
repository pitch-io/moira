(ns moira.module-test
  (:require [clojure.test :refer [are deftest is testing]]
            [moira.module :as module]
            [moira.test.async :refer [async-do async-let]]
            [moira.transition :as transition]
            [promesa.core :as p]))

(deftest postwalk-deps-test
  (testing "Walk deps depth-first in post-order"
    (is (= [:a :c :e :d :b :h :i :g :f]
           (module/postwalk-deps {:a {}
                                  :b {:deps [:a :d]}
                                  :c {}
                                  :d {:deps [:c :e]}
                                  :e {}
                                  :f {:deps [:b :g]}
                                  :g {:deps [:i]}
                                  :h {}
                                  :i {:deps [:h]}}
                                 :f)))))

(deftest dependency-chain-test
  (let [app {:module-a {:deps #{:module-c}}
             :module-b {:deps #{:module-a}}
             :module-c {}
             :module-d {:deps #{:module-a :module-b :module-c}}
             :module-e {}
             :module-f {:deps #{:module-e}}}]
    (testing "resolve chain of dependencies"
      (are [ks chain] (= chain (module/dependency-chain app ks))
        [:module-a] [:module-c :module-a]
        [:module-b] [:module-c :module-a :module-b]
        [:module-c] [:module-c]
        [:module-d] [:module-c :module-a :module-b :module-d]
        [:module-f] [:module-e :module-f]
        [:module-a :module-b :module-c] [:module-c :module-a :module-b]
        [:module-a :module-f] [:module-c :module-a :module-e :module-f]))))

(deftest abort-on-cyclic-dependency-chain-test
  (let [app {:module-a {:deps #{:module-c}}
             :module-b {:deps #{:module-a}}
             :module-c {:deps #{:module-b}}}
        !ex (atom nil)]
    (try
      (doall
       (module/dependency-chain app [:module-a]))
      (catch :default e
        (reset! !ex e))
      (finally
        (testing "Throw when dependency cycle is detected"
          (is (module/ex-cyclic-deps? @!ex)))))))

(deftest execute-test
  (let [ctx {::calls []}
        interceptor (fn [s]
                      {:enter #(update % ::calls conj (str s ">"))
                       :leave #(update % ::calls conj (str "<" s))})]
    (async-let
     [{::keys [calls] ::module/keys [current]}
      (module/execute ctx :module-a [(interceptor "1")
                                     (interceptor "2")
                                     (interceptor "3")])]
      (testing "make current module available to context"
        (is (= :module-a current)))
      (testing "execute interceptors"
        (is (= ["1>" "2>" "3>" "<3" "<2" "<1"] calls))))))

(deftest step-interceptor-update-state-test
  (let [!args (atom nil)
        app {:module-a {:deps #{:module-b :module-c}
                        :init (fn [state & args]
                                (reset! !args args)
                                (p/resolved (conj state "init")))
                        :state ["before"]}
             :module-b {:export (constantly "export-b")}
             :module-c {:export (constantly "export-c")}}
        {:keys [enter]} (module/step :init "some more context")]
    (async-let [ctx (enter {::transition/app app
                            ::module/current :module-a})]
      (testing "update state"
        (is (= ["before" "init"]
               (get-in ctx [::transition/app :module-a :state]))))
      (let [[exports module-key & additional-args] @!args]
        (testing "provide exports"
          (is (= {:module-b "export-b" :module-c "export-c"} exports)))
        (testing "pass module key"
          (is (= :module-a module-key)))
        (testing "forward additional arguments"
          (is (= ["some more context"] additional-args)))))))

(deftest step-interceptor-keep-state-test
  (let [app {:module-a {:state ["before"]}}
        {:keys [enter]} (module/step :init)]
    (async-let [ctx (enter {::transition/app app
                            ::module/current :module-a})]
      (testing "do not alter state"
        (is (= ["before"]
               (get-in ctx [::transition/app :module-a :state])))))))

(deftest step-interceptor-with-plugin-step-test
  (let [app {:module-a {:init #(conj % "init")
                        :plugins [{:init (fn [f]
                                           (fn [state ctx k & args]
                                             (p/as-> state s
                                               (conj s "plugin-1")
                                               (apply f s ctx k args))))}
                                  {:init (fn [f]
                                           (fn [state ctx k & args]
                                             (p/as-> state s
                                               (apply f s ctx k args)
                                               (conj s "plugin-2"))))}]
                        :state ["before"]}}
        {:keys [enter]} (module/step :init)]
    (async-let [ctx (enter {::transition/app app
                            ::module/current :module-a})]
      (testing "update state"
        (is (= ["before" "plugin-1" "init" "plugin-2"]
               (get-in ctx [::transition/app :module-a :state])))))))

(deftest step-interceptor-with-plugin-export-test
  (let [!exports (atom nil)
        app {:module-a {:deps #{:module-b}
                        :init (fn [_ exports] (reset! !exports exports))}
             :module-b {:export (constantly {:some "thing"})
                        :plugins [{:export
                                   (fn [f]
                                     (fn [s]
                                       (-> s f (assoc :extra "plugin-3"))))}]}}
        {:keys [enter]} (module/step :init)]
    (async-do
      (enter {::transition/app app
              ::module/current :module-a})
      (testing "extend export"
        (is (= {:some "thing" :extra "plugin-3"} (:module-b @!exports)))))))

(deftest enter-interceptor-test
  (let [{:keys [enter leave]} (module/enter :inited)]
    (testing "pass through when target state not present"
      (let [ctx {::transition/app {:my-module {}}
                 ::module/current :my-module
                 ::module/queue #queue [{:name ::tx-1} {:name ::tx-2}]}]
        (is (= ctx (enter ctx enter)))))
    (testing "terminate when already in target state"
      (is (empty? (-> {::transition/app {:my-module {:tags #{:inited}}}
                       ::module/current :my-module
                       ::module/queue #queue [{:name ::tx-1} {:name ::tx-2}]}
                      enter
                      (get ::module/queue)))))
    (testing "tag with new state"
      (is (= #{:loaded :inited}
             (-> {::transition/app {:my-module {:tags #{:loaded}}}
                  ::module/current :my-module}
                 leave
                 (get-in [::transition/app :my-module :tags])))))
    (testing "create tags if not already present"
      (is (= #{:inited}
             (-> {::transition/app {:my-module {}}
                  ::module/current :my-module}
                 leave
                 (get-in [::transition/app :my-module :tags])))))))

(deftest exit-interceptor-test
  (let [{:keys [enter leave]} (module/exit :booted)]
    (testing "pass through when in target state"
      (let [ctx {::transition/app {:my-module {:tags #{:booted}}}
                 ::module/current :my-module
                 ::module/queue #queue [{:name ::tx-1} {:name ::tx-2}]}]
        (is (= ctx (enter ctx)))))
    (testing "terminate when not in target state"
      (is (empty? (-> {::transition/app {:my-module {:tags #{:other}}}
                       ::module/current :my-module
                       ::module/queue #queue [{:name ::tx-1} {:name ::tx-2}]}
                      enter
                      (get ::module/queue)))))
    (testing "terminate when no tags are present"
      (is (empty? (-> {::transition/app {:my-module {}}
                       ::module/current :my-module
                       ::module/queue #queue [{:name ::tx-1} {:name ::tx-2}]}
                      enter
                      (get ::module/queue)))))
    (testing "remove state from tags"
      (is (= #{:ready}
             (-> {::transition/app {:my-module {:tags #{:ready :booted}}}
                  ::module/current :my-module}
                 leave
                 (get-in [::transition/app :my-module :tags])))))))

(deftest only-interceptor-test
  (let [{:keys [enter]} (module/only :running)]
    (testing "pass through when in target state"
      (let [ctx {::transition/app {:my-module {:tags #{:running}}}
                 ::module/current :my-module
                 ::module/queue #queue [{:name ::tx-1} {:name ::tx-2}]}]
        (is (= ctx (enter ctx)))))
    (testing "terminate when not in target state"
      (is (empty? (-> {::transition/app {:my-module {}}
                       ::module/current :my-module
                       ::module/queue #queue [{:name ::tx-1} {:name ::tx-2}]}
                      enter
                      (get ::module/queue)))))))

(deftest with-plugins-test
  (let [plugin {:foo (fn [f]
                       (fn [s]
                         {:foo (f s)}))
                :switch (fn [f]
                          (fn [state ctx k & args]
                            (p/as-> state s
                              (conj s "before")
                              (apply f s ctx k args)
                              (conj s "after"))))}
        {:keys [state switch] :as module} (module/with-plugins
                                            {:plugins [plugin]
                                             :state ["initial"]
                                             :switch #(conj % "switch")})]
    (async-let [state* (switch state)]
      (testing "skip unapplicable"
        (is (false? (contains? module :foo))))
      (testing "wrap function"
        (is (= ["initial" "before" "switch" "after"] state*))))))

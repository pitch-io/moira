(ns moira.context-test
  (:require [clojure.test :refer [are deftest is testing]]
            [moira.context :as context]
            [moira.test.async :refer [async-let async-then]]
            [moira.test.util :as util]
            [promesa.core :as p]))

(def ^:private empty-test-ctx {::calls []})

(defn- track [ctx s]
  (update ctx ::calls conj s))

(defn- interceptor
  ([s] (interceptor s {}))
  ([s m]
   (merge {:name s
           :enter #(track % (str s ">"))
           :error #(track % (str "!" s))
           :leave #(track % (str "<" s))}
          m)))

(def ^:private n (namespace ::_))

(def ^:private test-ctx
  (-> empty-test-ctx
      (context/stack n [(interceptor "1") (interceptor "2")])
      (context/enqueue n [(interceptor "3") (interceptor "4")])))

(deftest execution-order-test
  (async-let [{::keys [calls]}
              (context/execute
               empty-test-ctx
               n
               [(interceptor "1")
                (interceptor "2")
                (interceptor "3")])]
    (testing "leave in reverse order"
      (is (= ["1>" "2>" "3>" "<3" "<2" "<1"] calls)))))

(deftest async-execution-test
  (async-let [{::keys [calls]}
              (context/execute
               empty-test-ctx
               n
               [(interceptor "1" {:enter #(p/delay 10 (track % "1>"))})
                (interceptor "2" {:leave #(p/promise (track % "<2"))})
                (interceptor "3")])]
    (testing "wait for async tasks to complete"
      (is (= ["1>" "2>" "3>" "<3" "<2" "<1"] calls)))))

(deftest catch-error-on-enter-test
  (let [!ctx (atom nil)
        !ex (atom nil)]
    (async-then
      (-> (context/execute
           empty-test-ctx
           n
           [{:error (partial reset! !ctx)}
            (interceptor "1")
            (interceptor "2" {:enter #(throw "2> failed!")})
            (interceptor "3")])
          (p/catch (partial reset! !ex))
          (p/finally
            (fn [_ _]
              (testing "abort execution and handle error"
                (is (= ["1>" "!3" "!2" "!1"] (get @!ctx ::calls))))
              (testing "make error available to context"
                (is (= "2> failed!" (ex-message (get @!ctx ::error)))))
              (testing "reject with error"
                (is (= "2> failed!" (ex-message @!ex))))))))))

(deftest catch-error-on-leave-test
  (let [!ctx (atom nil)
        !ex (atom nil)]
    (async-then
      (-> (context/execute
           empty-test-ctx
           n
           [{:error (partial reset! !ctx)}
            (interceptor "1")
            (interceptor "2" {:leave #(throw "<2 failed!")})
            (interceptor "3")])
          (p/catch (partial reset! !ex))
          (p/finally
            (fn [_ _]
              (testing "abort execution and handle error"
                (is (= ["1>" "2>" "3>" "<3" "!1"] (get @!ctx ::calls))))
              (testing "make error available to context"
                (is (= "<2 failed!" (ex-message (get @!ctx ::error)))))
              (testing "reject with error"
                (is (= "<2 failed!" (ex-message @!ex))))))))))

(deftest error-info-test
  (let [error (ex-info "Oupsie!" {::env :test})
        !ex (atom nil)]
    (async-then
      (-> (context/execute
           empty-test-ctx
           n
           [(interceptor "failing" {:enter #(throw error)})])
          (p/catch (partial reset! !ex))
          (p/finally
            (fn [_ _]
              (testing "transfer error message"
                (is (= "Oupsie!" (ex-message @!ex))))
              (testing "add info about error details"
                (are [k v] (= v (get (ex-data @!ex) k))
                  ::context/ex error
                  ::context/name "failing"
                  ::context/stage :enter))))))))

(deftest enqueue-test
  (let [{::keys [queue stack]}
        (context/enqueue test-ctx
                         n
                         [(interceptor "5") (interceptor "6")])]
    (testing "add to queue"
      (is (= ["3" "4" "5" "6"] (map :name (util/peek-seq queue)))))
    (testing "keep stack as is"
      (is (= ["2" "1"] (map :name (util/peek-seq stack)))))))

(deftest terminate-test
  (let [{::keys [queue stack]} (context/terminate test-ctx n)]
    (testing "clear queue"
      (is (empty? queue)))
    (testing "preserve queue type"
      (is (= [:first :second] (util/peek-seq (conj queue :first :second)))))
    (testing "keep stack as is"
      (is (= ["2" "1"] (map :name (util/peek-seq stack)))))))

(deftest stack-test
  (let [{::keys [queue stack]}
        (context/stack test-ctx
                       n
                       [(interceptor "5") (interceptor "6")])]
    (testing "add to stack"
      (is (= ["6" "5" "2" "1"] (map :name (util/peek-seq stack)))))
    (testing "keep queue as is"
      (is (= ["3" "4"] (map :name (util/peek-seq queue)))))))

(deftest execute-all-test
  (async-let [{::keys [calls]} (context/execute-all test-ctx n)]
    (testing "enter and leave all remaining"
      (is (= ["3>" "4>" "<4" "<3" "<2" "<1"] calls)))))

(deftest execute-1-enter-test
  (async-let [{::keys [calls]} (context/execute-1 test-ctx n)]
    (testing "enter next only"
      (is (= ["3>"] calls)))))

(deftest execute-1-leave-test
  (async-let [{::keys [calls]}
              (-> test-ctx
                  (context/terminate n)
                  (context/execute-1 n))]
    (testing "leave next only"
      (is (= ["<2"] calls)))))

(deftest execute-1-none-test
  (async-let [{::keys [calls] :as ctx}
              (context/execute-1 empty-test-ctx n)]
    (testing "fail gracefully"
      (is (= [] calls))
      (is (not (context/error? ctx n))))))

(deftest enter-1-test
  (async-let [{::keys [calls]} (context/enter-1 test-ctx n)]
    (testing "enter next only"
      (is (= ["3>"] calls)))))

(deftest enter-1-none-test
  (async-let [{::keys [calls] :as ctx}
              (-> test-ctx
                  (context/terminate n)
                  (context/enter-1 n))]
    (testing "fail gracefully"
      (is (= [] calls))
      (is (not (context/error? ctx n))))))

(deftest leave-1-test
  (async-let [{::keys [calls]} (context/leave-1 test-ctx n)]
    (testing "leave next only"
      (is (= ["<2"] calls)))))

(deftest leave-1-none-test
  (async-let [{::keys [calls]}
              (-> test-ctx
                  (assoc ::stack '())
                  (context/leave-1 n))]
    (testing "fail gracefully"
      (is (= [] calls)))))

(deftest leave-1-error-test
  (let [ex (js/Error. "Failed!")]
    (async-let [{::keys [calls]}
                (-> test-ctx
                    (assoc ::error ex)
                    (context/leave-1 n))]
      (testing "next error only"
        (is (= ["!2"] calls))))))

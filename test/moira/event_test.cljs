(ns moira.event-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [moira.event :as event]))

(def ^:private now 1652719778791)

(defn- test-now
  ([] (constantly now))
  ([t] (constantly (.getTime t))))

(defn- test-date
  ([] (constantly (js/Date. now)))
  ([t] (constantly t)))

(def ^:private test-app-id "99qi9djntfqr")

(defn test-counter
  ([] (volatile! nil))
  ([t s] (volatile! [(.getTime t) (js/parseInt s 36)])))

(defn test-generator
  ([] (event/->EventIdGenerator (test-now) test-app-id (test-counter)))
  ([id]
   (reify
     event/IdGenerator
     (next-id [_] id))))

(def test-factory
  (event/->EventFactory (test-date) (test-generator)))

(deftest create-event-id-test
  (let [t #inst "2021-09-16T08:44:38.677"
        generator (event/->EventIdGenerator (test-now t)
                                            test-app-id
                                            (test-counter t "0456"))
        id (event/next-id generator)]
    (testing "Creates event id instance"
      (is (event/event-id? id)))
    (testing "Encodes creation date"
      (is (= t (event/date id))))
    (testing "Encodes app-id context"
      (is (= test-app-id (event/app id))))
    (testing "Encodes counter suffix"
      (is (= "0457" (event/counter id))))))

(deftest inspect-event-id-test
  (let [id (event/next-id (test-generator))]
    (testing "Inspect value string"
      (is (= (str (event/date-prefix id)
                  (event/app id)
                  (event/counter id))
             (str id))))))

(deftest increase-counter-test
  (let [generator (test-generator)]
    (testing "Increase counter"
      (is (< (event/next-count generator)
             (event/next-count generator)
             (event/next-count generator))))))

(deftest event-id-equality-test
  (testing "Compare for equality"
    (let [id (event/next-id (test-generator))]
      (is (= id (event/string->event-id (str id)))))))

(deftest event-id-sorting-test
  (testing "Compare for sorting"
    (let [generator (test-generator)
          id-1 (event/next-id generator)
          id-2 (event/next-id generator)
          id-3 (event/next-id generator)]
      (is (= [id-1 id-2 id-3] (sort [id-2 id-3 id-1]))))))

(deftest event-id-tagged-literal-test
  (testing "Read tagged literal"
    (is (= (event/string->event-id "ktpgcdxgxhmkwoookboe00")
           #event/id "ktpgcdxgxhmkwoookboe00"))
    (is (thrown? js/Error #event/id :ktpgcdxgxhmkwoookboe00))))

(deftest optimized-even-id-hash-test
  (testing "Optimize hashing"
    (is (= (hash "ktpgcdxgxhmkwoookboe00")
           (hash #event/id "ktpgcdxgxhmkwoookboe00")))))

(deftest create-event-test
  (testing "Returns event instance"
    (is (s/valid? ::event/event (event/->event test-factory {:type ::test}))))
  (testing "Not valid without type"
    (is (not (s/valid? ::event/event (event/->event test-factory {})))))
  (testing "Drops invalid keys"
    (is (= #{:data :date :id :type} (->> {:data [1 2 3]
                                          :foo "bar"
                                          :type :emitted}
                                         (event/->event test-factory)
                                         keys
                                         set))))
  (testing "Keep data value intact"
    (is (= [1 2 3] (->> {:data [1 2 3] :type :emitted}
                        (event/->event test-factory)
                        :data)))))

(deftest generate-id-test
  (let [id #event/id "ktpgcdxgxhmkwoookboe00"
        generator (test-generator id)
        factory (event/->EventFactory (test-date) generator)]
    (testing "Generate id"
      (is (= id (->> {:type :emitted}
                     (event/->event factory)
                     :id))))
    (testing "Throws with id present"
      (is (thrown? js/Error
                   (->> {:id #event/id "ktpgcdxgxhmkwoookboe99"
                         :type :emitted}
                        (event/->event factory)))))))

(deftest ensure-date-on-create-test
  (let [t #inst "2022-05-16T17:46:24.778-00:00"
        factory (event/->EventFactory (test-date t) (test-generator))
        date #inst "2021-09-16T08:44:38.677"]
    (testing "Creates date when not given"
      (is (= t (->> {:type :emitted}
                    (event/->event factory)
                    :date))))
    (testing "Keeps given date"
      (is (= date (->> {:date date
                        :type :emitted}
                       (event/->event factory)
                       :date))))))

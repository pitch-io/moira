(ns moira.application-test
  (:require [clojure.test :refer [deftest is testing]]
            [moira.application :as application]
            [moira.module :as module]
            [moira.test.async :refer [async-do async-let]]
            [promesa.core :as p]))

(deftest inspect-system-state
  (let [system-map {:module-a {:state "initial state"
                               :pause #(p/resolved "--paused--")
                               :tags #{:started}}
                    :module-b {:deps #{:module-a}}}
        app (application/create system-map)]
    (application/pause! app [:module-a :module-b])
    (testing "get current value"
      (is (= system-map @app)))
    (async-let [app-state (application/then app identity)]
      (is (= "--paused--" (get-in app-state [:module-a :state]))))))

(deftest watch-system-state
  (let [app (application/create {:my-module {:state "idle"
                                             :start (constantly "started")}})
        !notifications (atom [])]
    (add-watch app ::test #(swap! !notifications
                                  conj
                                  (str (get-in %3 [:my-module :state])
                                       " -> "
                                       (get-in %4 [:my-module :state]))))
    (async-do
      (application/start! app [:my-module])
      (testing "watcher was notified"
        (is (= ["idle -> started"] @!notifications))))))

(deftest start-modules-test
  (let [app (application/create {:module-a {:deps #{:module-b}
                                            :state ["init-a"]
                                            :start #(conj % "start-a")}
                                 :module-b {:state ["init-b"]
                                            :start #(conj % "start-b")}})]
    (async-let [app-state (application/start! app [:module-a])]
      (testing "module is updated"
        (is (= ["init-a" "start-a"] (get-in app-state [:module-a :state]))))
      (testing "dependencies are updated"
        (is (= ["init-b" "start-b"] (get-in app-state [:module-b :state]))))
      (testing "app-log is injected"
        (is (contains? app-state :app-log)))
      (testing "modules are tagged"
        (is (= #{:started}
               (get-in app-state [:module-a :tags])
               (get-in app-state [:module-b :tags]))))
      (testing "update state"
        (is (= app-state @app))))))

(deftest provide-context-on-start
  (let [app (application/create {:module-a {:start (fn [_ _ k a]
                                                     {:name k :app a})}})]
    (async-let [app-state (application/start! app [:module-a])]
      (testing "module key is provided"
        (is (= :module-a (get-in app-state [:module-a :state :name]))))
      (testing "app instance is passed along"
        (is (= app (get-in app-state [:module-a :state :app])))))))

(deftest start-all-modules-test
  (let [app (application/create {:module-a {:start #(conj % "start-a")}
                                 :module-b {:start #(conj % "start-b")}})]
    (application/extend! app {:module-c {:start #(conj % "start-c")}})
    (async-let [app-state (application/start! app)]
      (testing "start all modules by default"
        (is (= ["start-a"] (get-in app-state [:module-a :state])))
        (is (= ["start-b"] (get-in app-state [:module-b :state])))
        (is (= ["start-c"] (get-in app-state [:module-c :state])))))))

(deftest stop-modules-test
  (let [app (application/create {:module-a {:deps #{:module-b}
                                            :state ["init-a" "start-a"]
                                            :stop #(conj % "stop-a")
                                            :tags #{:started}}
                                 :module-b {:state ["init-b" "start-b"]
                                            :stop #(conj % "stop-b")
                                            :tags #{:started}}})]
    (async-let [app-state (application/stop! app [:module-a])]
      (testing "module is updated"
        (is (= ["init-a" "start-a" "stop-a"]
               (get-in app-state [:module-a :state]))))
      (testing "dependencies are not updated"
        (is (= ["init-b" "start-b"]
               (get-in app-state [:module-b :state]))))
      (testing "modules are untagged"
        (is (= #{} (get-in app-state [:module-a :tags]))))
      (testing "update state"
        (is (= app-state @app))))))

(deftest provide-context-on-stop
  (let [app (application/create {:module-a {:stop (fn [_ _ k a]
                                                    {:name k :app a})
                                            :tags #{:started}}})]
    (async-let [app-state (application/stop! app [:module-a])]
      (testing "module key is provided"
        (is (= :module-a (get-in app-state [:module-a :state :name]))))
      (testing "app instance is passed along"
        (is (= app (get-in app-state [:module-a :state :app])))))))

(deftest stop-all-modules-test
  (let [app (application/create {:module-a {:stop #(conj % "stop-a")
                                            :tags #{:started}}
                                 :module-b {:stop #(conj % "stop-b")
                                            :tags #{:started}}})]
    (application/extend! app {:module-c {:stop #(conj % "stop-c")
                                         :tags #{:started}}})
    (async-let [app-state (application/stop! app)]
      (testing "stop all modules by default"
        (is (= ["stop-a"] (get-in app-state [:module-a :state])))
        (is (= ["stop-b"] (get-in app-state [:module-b :state])))
        (is (= ["stop-c"] (get-in app-state [:module-c :state])))))))

(deftest pause-modules-test
  (let [app (application/create {:module-a {:deps #{:module-b}
                                            :state ["started-a"]
                                            :pause #(conj % "pause-a")
                                            :tags #{:started}}
                                 :module-b {:state ["started-b"]
                                            :pause #(conj % "pause-b")
                                            :tags #{:started}}
                                 :module-c {:state ["not-started"]
                                            :pause #(conj % "pause-c")
                                            :tags #{}}})]
    (async-let [app-state (application/pause! app [:module-a :module-c])]
      (testing "module is updated"
        (is (= ["started-a" "pause-a"] (get-in app-state [:module-a :state]))))
      (testing "dependencies are not updated"
        (is (= ["started-b"] (get-in app-state [:module-b :state]))))
      (testing "modules are tagged"
        (is (= #{:started :paused} (get-in app-state [:module-a :tags]))))
      (testing "idle modules are not paused"
        (is (= ["not-started"] (get-in app-state [:module-c :state])))
        (is (= #{} (get-in app-state [:module-c :tags]))))
      (testing "update state"
        (is (= app-state @app))))))

(deftest provide-context-on-pause
  (let [app (application/create {:module-a {:pause (fn [_ _ k a]
                                                     {:name k :app a})
                                            :tags #{:started}}})]
    (async-let [app-state (application/pause! app [:module-a])]
      (testing "module key is provided"
        (is (= :module-a (get-in app-state [:module-a :state :name]))))
      (testing "app instance is passed along"
        (is (= app (get-in app-state [:module-a :state :app])))))))

(deftest pause-all-modules-test
  (let [app (application/create {:module-a {:pause #(conj % "pause-a")
                                            :tags #{:started}}
                                 :module-b {:pause #(conj % "pause-b")
                                            :tags #{:started}}})]
    (application/extend! app {:module-c {:pause #(conj % "pause-c")
                                         :tags #{:started}}})
    (async-let [app-state (application/pause! app)]
      (testing "pause all modules by default"
        (is (= ["pause-a"] (get-in app-state [:module-a :state])))
        (is (= ["pause-b"] (get-in app-state [:module-b :state])))
        (is (= ["pause-c"] (get-in app-state [:module-c :state])))))))

(deftest resume-modules-test
  (let [app (application/create {:module-a {:deps #{:module-b}
                                            :state ["started-a"]
                                            :resume #(conj % "resume-a")
                                            :tags #{:started :paused}}
                                 :module-b {:state ["started-b"]
                                            :resume #(conj % "resume-b")
                                            :tags #{:started :paused}}})]
    (async-let [app-state (application/resume! app [:module-a])]
      (testing "module is updated"
        (is (= ["started-a" "resume-a"] (get-in app-state [:module-a :state]))))
      (testing "dependencies are updated"
        (is (= ["started-b" "resume-b"] (get-in app-state [:module-b :state]))))
      (testing "modules are tagged"
        (is (= #{:started}
               (get-in app-state [:module-a :tags])
               (get-in app-state [:module-b :tags]))))
      (testing "update state"
        (is (= app-state @app))))))

(deftest provide-context-on-resume
  (let [app (application/create {:module-a {:resume (fn [_ _ k a]
                                                      {:name k :app a})
                                            :tags #{:paused}}})]
    (async-let [app-state (application/resume! app [:module-a])]
      (testing "module key is provided"
        (is (= :module-a (get-in app-state [:module-a :state :name]))))
      (testing "app instance is passed along"
        (is (= app (get-in app-state [:module-a :state :app])))))))

(deftest resume-all-modules-test
  (let [app (application/create {:module-a {:resume #(conj % "resume-a")
                                            :tags #{:paused}}
                                 :module-b {:resume #(conj % "resume-b")
                                            :tags #{:paused}}})]
    (application/extend! app {:module-c {:resume #(conj % "resume-c")
                                         :tags #{:paused}}})
    (async-let [app-state (application/resume! app)]
      (testing "resume all modules by default"
        (is (= ["resume-a"] (get-in app-state [:module-a :state])))
        (is (= ["resume-b"] (get-in app-state [:module-b :state])))
        (is (= ["resume-c"] (get-in app-state [:module-c :state])))))))

(deftest initialize-app-with-overrides
  (let [app (application/create {:module-a {:secret "replace me!"
                                            :start (constantly [1 2 3])}})]
    (async-let [app-state (application/init! app {:module-a {:secret "123"}})]
      (testing "override module definition"
        (is (= "123" (get-in app-state [:module-a :secret]))))
      (testing "start app"
        (is (= [1 2 3] (get-in app-state [:module-a :state]))))
      (testing "update app state"
        (is (= app-state @app))))))

(deftest load-modules-into-running-app
  (let [app (application/create {:module-a {}})]
    (async-let [app-state (application/load! app {:module-b
                                                  {:state ["initial state"]
                                                   :start #(conj % "additional state")}})]
      (testing "Add definition and start module"
        (is (= ["initial state" "additional state"]
               (get-in app-state [:module-b :state]))))
      (testing "update state"
        (is (= app-state @app))))))

(deftest protect-existing-modules-on-load
  (let [app (application/create {:my-module {:state "original state"}})
        stop (constantly nil)]
    (async-let [app-state (application/load! app {:my-module {:state "new state"
                                                              :stop stop}})]
      (testing "Do not override existing items"
        (is (= "original state" (get-in app-state [:my-module :state]))))
      (testing "Extend to include new items"
        (is (= stop (get-in app-state [:my-module :stop])))))))

(deftest only-start-provided-modules-on-load
  (let [app (application/create {:module-a {:start (constantly "started")}})]
    (async-let [app-state (application/load! app {:module-b
                                                  {:start (constantly "started")}})]
      (testing "Start new module"
        (is (= "started" (get-in app-state [:module-b :state]))))
      (testing "Do not start existing module"
        (is (nil? (get-in app-state [:module-a :state])))))))

(def test-module {:pause #(conj % "pause")
                  :resume #(conj % "resume")
                  :start #(p/resolved (conj % "start"))
                  :state []
                  :stop #(conj % "stop")})

(deftest chain-transitions
  (let [app (application/create {:my-module test-module})]
    (application/start! app) ; arity 1 call
    (application/pause! app)
    (application/resume! app [:my-module]) ; arity 2 call
    (application/load! app {:new-module test-module})
    (async-let [app-state (application/stop! app)]
      (testing "execute transitions in order"
        (is (= ["start" "pause" "resume" "stop"]
               (get-in app-state [:my-module :state]))))
      (testing "operate on most recent state"
        (is (= ["start" "stop"]
               (get-in app-state [:new-module :state])))))))

(deftest recover-from-error-when-chaining-transitions
  (let [!calls (atom [])
        !error (atom nil)
        !app-state (atom nil)
        app (application/create
             {:module-a {:state ["init-a"]
                         :start #(let [s (conj % "start-a")]
                                   (swap! !calls conj {:start-a s})
                                   s)}
              :module-b {:state ["init-b"]
                         :start #(let [s (conj % "start-b")]
                                   (swap! !calls conj {:start-b s})
                                   (throw "Error!")
                                   s)}})]
    (async-do
      (-> app
          (application/start! [:module-a :module-b])
          (p/catch (partial reset! !error)))
      (application/then app (partial reset! !app-state))
      (testing "update calls are triggered"
        (is (= [{:start-a ["init-a" "start-a"]}
                {:start-b ["init-b" "start-b"]}]
               @!calls)))
      (testing "promise is rejected with error"
        (is (= "Error!" (ex-message @!error))))
      (testing "resume from previous state"
        (is (= @!app-state @app))
        (is (= ["init-a"]
               (get-in @app [:module-a :state])))
        (is (= ["init-b"]
               (get-in @app [:module-b :state])))))))

(defn- switch! [app modules]
  (application/tx! app [(module/step :switch)] modules))

(deftest apply-transition
  (let [app (application/create {:module-a {:deps #{:module-b}
                                            :state ["init-a"]
                                            :switch #(conj % "switch-a")}
                                 :module-b {:state ["init-b"]
                                            :switch #(conj % "switch-b")}})]
    (async-let [app-state (switch! app [:module-a])]
      (testing "module is updated"
        (is (= ["init-a" "switch-a"] (get-in app-state [:module-a :state]))))
      (testing "dependencies are not updated"
        (is (= ["init-b"] (get-in app-state [:module-b :state]))))
      (testing "resolve with updated state"
        (is (= app-state @app))))))

(deftest apply-nested-transition
  (let [!app (atom nil)
        !tail (atom nil)]
    (reset! !app (application/create
                  {:module-a
                   {:state ["init-a"]
                    :switch (fn [state]
                              (reset! !tail (switch! @!app [:module-b]))
                              (conj state "switch-a"))}
                   :module-b
                   {:state ["init-b"]
                    :switch #(conj % "switch-b")}}))
    (async-let [outer (switch! @!app [:module-a])
                inner @!tail]
      (testing "outer update is applied first"
        (is (= ["init-a" "switch-a"]
               (get-in outer [:module-a :state])))
        (is (= ["init-b"]
               (get-in outer [:module-b :state]))))
      (testing "nested update is applied later"
        (is (= ["init-a" "switch-a"]
               (get-in inner [:module-a :state])))
        (is (= ["init-b" "switch-b"]
               (get-in inner [:module-b :state])))))))

(deftest time-out-transition
  (let [!error (atom nil)
        app (application/create {:slow {:state "initial"
                                        :switch #(p/delay 100 "done")}})]
    (async-do
      (binding [application/*timeout* 0
                application/*warnings?* false]
        (-> app
            (switch! [:slow])
            (p/catch (partial reset! !error))))
      (testing "cancel transition"
        (is (= "initial" (get-in @app [:slow :state])))))))

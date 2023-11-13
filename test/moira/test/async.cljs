(ns moira.test.async
  (:require-macros moira.test.async)
  (:require cljs.core.async
            cljs.test
            [promesa.core :as p]))

(def ^:dynamic *timeout* 500)

(defn- timeout-error? [ex]
    (instance? p/TimeoutException ex))

(defn- ex-timeout [ex]
  (ex-info (str "Test did not terminate within " *timeout* "ms")
           {:ex ex :type ::timeout}))

(defn timeout [p]
  (-> (p/timeout p *timeout*)
      (p/catch timeout-error?
        (fn [ex]
          (cljs.test/do-report
           {:actual (ex-timeout ex)
            :expected nil
            :message "Async test block timed out."
            :type :error})))
      (p/catch (fn [ex]
                 (cljs.test/do-report
                  {:actual ex
                   :expected nil
                   :message "Uncaught exception, not in assertion."
                   :type :error})))))


(ns moira.test.async)

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defmacro async-go
  "Wrap body in `core.async/go` and return asynchronous test that times out,
  when the block does not terminate in time. Errors thrown inside the coroutine
  are reported to the test runner."
  [& body]
  `(cljs.test/async done#
     (let [<timeout># (cljs.core.async/timeout moira.test.async/*timeout*)
           <test># (cljs.core.async/go
                    (try
                      ~@body
                      (catch :default ex#
                        (cljs.test/do-report
                         {:actual ex#
                          :expected nil
                          :message "Uncaught exception, not in assertion."
                          :type :error}))))]
       (cljs.core.async/go
        (let [c# (second (cljs.core.async/alts! [<timeout># <test>#]))]
          (when (= <timeout># c#)
            (cljs.test/do-report
             {:actual (js/Error. (str "Test did not terminate within "
                                      moira.test.async/*timeout* "ms"))
              :expected nil
              :message "Async test block timed out."
              :type :error}))
          (done#))))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defmacro async-then
  "Return asynchronous test that times out, when `js/Promise` returned from the
  block does not terminate in time. Errors thrown inside the coroutine are
  reported to the test runner."
  [& body]
  `(cljs.test/async done#
     (-> (try
           ~@body
           (catch :default ex#
             (promesa.core/rejected ex#)))
         moira.test.async/timeout
         (promesa.core/finally done#))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defmacro async-do
  "Return asynchrounous test that executes body waiting for each promise within
  to resolve before continuing to the next form. Terminates early on timeout
  and reports errors from the coroutines to the test runner."
  [& body]
  `(cljs.test/async done#
     (-> (promesa.core/do ~@body)
         moira.test.async/timeout
         (promesa.core/finally done#))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defmacro async-let
  "Return asynchrounous test that waits for all promises on the bindings before
  executing body with the bound results. Terminates early on timeout and
  reports errors from the coroutines to the test runner."
  [bindings & body]
  `(cljs.test/async done#
     (-> (promesa.core/let ~bindings ~@body)
         moira.test.async/timeout
         (promesa.core/finally done#))))

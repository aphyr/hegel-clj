(ns hegel-clj.clojure-test
  "Hegel integration with clojure.test."
  (:require [hegel-clj [generator :as g]
                       [core :as h]]
            [clojure [pprint :refer [pprint]]
                     [test :as ct]]
            [clojure.tools.logging :refer [info warn]])
  (:import (org.opentest4j AssertionFailedError)))

(defn pass?
  "Did every clojure.test report pass?"
  [reports]
  (every? (comp #{:pass} :type) reports))

(defmacro capture-reports
  "Evaluates body for clojure.test reports as a side effect. Returns a vector
  of those reports. In the final test phase, also sends those final reports to
  the normal clojure.test reporter."
  [& body]
  `(let [og-report# ct/report
         reports# (atom [])
         report# (fn ~'report [event#]
                   (when (h/final?) (og-report# event#))
                   (swap! reports# conj event#))]
     (binding [ct/report report#]
       ~@body)
     @reports#))

(defn origin
  "Produces an origin string for a set of reports. Hegel is... vague about what
  the origin should be; you don't want to be *too* specific or else it will
  explode the state space. I think just the file and line, perhaps?"
  [reports]
  (let [report (->> reports
                    (remove (comp #{:pass} :type))
                    first)]
    (str (:file report) ":" (:line report))))

(defmacro with
  "Can be embedded within a clojure.test deftest to generate some series of
  values and test whether they produce correct results. Takes an options map
  for `hegel-clj.test/test!*`, a binding vector, then a body. Evaluates
  body roughly `test-cases` times, with bindings provided by
  `hegel-clj.gen/let`. You can generate more values using `draw!` or
  `hegel-clj/let`, if needed. Make test assertions using `clojure.test/is`, as
  usual. Failing tests will be automatically shrunk and re-run with minimal
  examples. Log these examples using `note`.

  For example:

      (deftest reverse-test
        ; Generate roughly a hundred integers of vectors
        (with {:test-cases 100, :seed 5}
              [xs (gen/vector (g/integer))]

          ; Print out only the smallest vectors that make this fail
          (fprn :xs xs)

          ; Is sorting the same as reversing? Clearly not, so this will fail,
          ; likely with a two-element vector of different numbers.
          (is (= (sort xs)
              (reverse xs)))))

  `with` suppresses clojure.test reporting until the final phase, where it
  reports as normal. You can broadly pretend that this is a clojure.test case
  where you just happened to guess the right small inputs to reproduce a
  failing bug."
  [opts bindings & body]
  `(let [res# (h/test! ~opts
                       ; Generate values and evaluate body, recording
                       ; clojure.test reports
                       (let [reports# (capture-reports
                                        (g/let ~bindings
                                          ~@body))]
                         ; If every clojure.test assertion passed, this test
                         ; case does too.
                         (if (pass? reports#)
                           nil
                           ; Otherwise, tell Hegel about it.
                           (throw (AssertionFailedError.
                                    (origin reports#))))))]
     res#))

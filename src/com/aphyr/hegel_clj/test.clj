(ns com.aphyr.hegel-clj.test
  "The main API for hegel-clj."
  (:require [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :refer [info warn]]
            [com.aphyr.hegel-clj [client :as c]]))

;; Global/dynamic state

(def core
  "As a convenience, we keep a global client around and automatically spin it
  upon the first request."
  (delay
    (c/start-core!)))

(def ^:dynamic *test-case-stream-id*
  "As another convenience, we store the current test case stream ID so you
  don't have to thread it between test cases and calls to generate."
  nil)

;; Running tests

(defn run-test!*
  "Runs a test. This version is more functional; for a more convenient,
  stateful version, use deftest. Options are:

      :test-cases     The number of test cases to run
      :seed           A random seed
      :derandomize    If true, and seed is not set, derives a determinstic
                      seed from database-key
      :database-key   A stable database key for this test
      :database       A path to the DB directory
      :suppress-health-check  A vector of health check keywords to suppress:
                              any of :test-cases-too-large, :filter-too-much,
                              :too-slow, :large-initial-test-case

  Takes a function `(case-fn)` which will be invoked approximately `test-cases`
  times, with zero arguments. This function can call `(gen/int)` etc. to
  generate values, and should return a map which explains whether the case was
  valid or not:

      {:status :valid}
      {:status :invalid}
      {:status :interesting
       :origin \"...\"}

  Returns a map describing the results of the test, of the form:

      :passed?                Did all tests pass?)
      :test-cases             How many test cases were executed?
      :valid-test-cases       How many of them were valid
      :invalid-test-cases     How many of them were invalid
      :interesting-test-cases How many of them were interesting (e.g. a bug)
      :seed                   The random seed used
      :flaky?                 Hegel thought this test was non-deterministic
      :health-check-failure?  Did a health check fail during the test?

  May also throw an exception map like {:type :hegel-error, :message \"...\"}."
  [opts case-fn]
  (c/run-test! @core opts
               (fn stream-id-wrapper [stream-id]
                       (binding [*test-case-stream-id* stream-id]
                         (case-fn)))))

(defmacro run-test!
  "Macro form of run-test!*; takes a body, rather than a function."
  [opts & body]
  `(run-test!* ~opts (fn ~'case [] ~@body)))

;; Generators

(defn gen
  "Generates a random value from the provided schema. Use `hegel-clj.gen` to
  construct a schema, like so:

      (gen (gen/one-of (gen/boolean) (gen/integer)))"
  [schema]
  (c/generate! @core *test-case-stream-id* schema))

;; Final cases and logging

(defmacro when-final
  "Evaluates body only if this is a final test case. Helpful for logging."
  [& body]
  `(when c/*final-case?*
     ~@body))

(defmacro finfo
  "Logging macro. Calls core.logging/info, but only when in a final test
  run."
  [& args]
  `(when-final (info ~@args)))

(defmacro fwarn
  "Logging macro. Calls core.logging/warn, but only when in a final test
  run."
  [& args]
  `(when-final (warn ~@args)))

(defmacro fprn
  "Like clojure prn, but only when in a final test run."
  [& args]
  `(when-final (prn ~@args)))

(defmacro fpprint
  "Like clojure pprint, but only when in a final test run."
  [& args]
  `(when-final (pprint ~@args)))

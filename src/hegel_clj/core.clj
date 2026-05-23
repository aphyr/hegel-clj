(ns hegel-clj.core
  "The ain API for hegel-clj."
  (:require [clojure [pprint :refer [pprint]]
                     [test :as ct]]
            [clojure.tools.logging :refer [info warn]]
            [hegel-clj [client :as c]])
  (:import (clojure.lang ExceptionInfo)))

;; Global/dynamic state

(defonce ^{:doc "As a convenience, we keep a global client around and automatically spin it upon the first request."}
  client
  (delay
    (c/start-core!)))

(def ^:dynamic *client*
  "We make the client accessible as a dynamic variable, so that generators can
  access it without having to thread arguments through every phase of
  generation."
  nil)

(def ^:dynamic *test-case-stream-id*
  "As another convenience, we store the current test case stream ID so you
  don't have to thread it between test cases and calls to generate."
  nil)

(def next-global-span-type
  "The Core can generate span types, but we can also generate them globally
  (e.g. at macroexpand time). We use this in g/let to start spans. Global spans
  are negative."
  (atom -1))

(defn gen-global-span-type!
  "Generates a new global span. This is available at macroexpand time."
  []
  (swap! next-global-span-type dec))

;; Running tests

(defn run-test!*
  "Runs a test. This version is more functional; for a more convenient,
  stateful version, use deftest. Options are:

      :test-cases     The number of test cases to run
      :seed           A random seed
      :derandomize    If true, and seed is not set, derives a deterministic
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
  (let [client @client]
    (c/run-test! client opts
                 (fn stream-id-wrapper [stream-id]
                   (binding [*client*              client
                             *test-case-stream-id* stream-id]
                     (case-fn))))))

(defmacro run-test!
  "Macro form of run-test!*; takes a body, rather than a function."
  [opts & body]
  `(run-test!* ~opts (bound-fn ~'case [] ~@body)))

;; Generators

(defn gen
  "Generates a random value from the provided schema. Use `hegel-clj.gen` to
  construct a schema, like so:

      (gen (gen/one-of (gen/boolean) (gen/integer)))"
  [schema]
  (c/generate! (or *client* @client) *test-case-stream-id* schema))

(defn sample*
  "Samples up to `n` values from the provided function `(f)`, which presumably
  calls `gen`. Helpful for debugging generators."
  [n f]
  (let [out (atom [])]
    (run-test! {:test-cases n}
               (swap! out conj (f))
               {:status :valid})
    @out))

(defmacro sample
  "Evaluates body up to n times, returning a vector of results. Useful for
  debugging generators."
  [n & body]
  `(sample* ~n (bound-fn ~'sample [] ~@body)))

(defn new-collection!
  "See client/new-collection!"
  [opts]
  (c/new-collection! (or *client* @client) *test-case-stream-id* opts))

(defn collection-more?
  "See client/collection-more?"
  [collection-id]
  (c/collection-more? (or *client* @client) *test-case-stream-id*
                      collection-id))

(defn collection-reject!
  "See client/collection-reject!"
  [collection-id]
  (c/collection-reject! (or *client* @client) *test-case-stream-id*
                        collection-id))

(defn gen-span-type!
  "Returns a fresh span type for the current client."
  []
  (c/gen-span-type! (or *client* @client)))

(defn start-span!
  "Starts a span of the given type on the current client."
  [span-type]
  (c/start-span! (or *client* @client) *test-case-stream-id* span-type))

(defn stop-span!
  "Stops a span on the current client."
  []
  (c/stop-span! (or *client* @client) *test-case-stream-id*))

(defmacro with-span*
  "Evaluates body with a span of the given type. If the body throws, I'm not
  sure *what* to do; I'm going to try closing the span, but I imagine that's
  probably asking for trouble sometimes."
  [span-type & body]
  `(do (start-span! ~span-type)
       (try ~@body
             (catch ExceptionInfo e#
               (if (identical? :hegel-clj/stop-test (:type (ex-data e#)))
                 ; If we try and stop a span after Hegel gets in this state
                 ; you'll never get a response and everything breaks. This is
                 ; fragile and bad. Maybe you just should never throw ever???
                 (throw e#)
                 (do ;(stop-span!)
                     (throw e#))))
             (catch Throwable t#
               ;(stop-span!)
               (throw t#)))))

(defmacro with-span
  "Evaluates body with a span. Generates a unique span type at macroexpand
  time."
  [& body]
  (let [type (gen-global-span-type!)]
    `(with-span* ~type ~@body)))

;; Final cases and logging

(defn final?
  "Returns true iff we're in the final pass of a test case."
  []
  c/*final-case?*)

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

(ns hegel-clj.core
  "The main API for hegel-clj."
  (:refer-clojure :exclude [let])
  (:require [clojure [core :as c]
                     [pprint :refer [pprint]]
                     [string :as str]
                     [test :as ct]]
            [clojure.tools.logging :refer [info warn]])
  (:import (clojure.lang ExceptionInfo)
           (dev.hegel Failure
                      Generator
                      HealthCheck
                      Hegel
                      Label
                      Phase
                      Reporter
                      RunReport
                      RunStatus
                      Settings
                      Stateful
                      TestCase
                      Verbosity)))

;; Global/dynamic state

(def ^:dynamic ^TestCase *test-case*
  "It's convenient to have an implicitly bound test case, so one can simply
  call (draw! generator) instead of threading the test case through."
  nil)

;; Running tests

(defn failure->map
  "Converts a Hegel test case Failure to a Clojure map of the form:

      {:draws
       :exception
       :reproduce-blob
       :caveat}"
  [^Failure f]
  {:draws (.draws f)
   :exception (.exception f)
   :reproduce-blob (.orElse (.reproduceBlob f) nil)
   :caveat (.orElse (.caveat f) nil)})

(defn test-fn!
  "Runs a test. This version is more functional; see run! for the macro form.
  Options are:

      :database       A hegel.Database
      :derandomize?   Whether to force deterministic (or not) input selection.
      :name           The string name of the property being tested.

      :reproduce-failure
                      A string blob (e.g. the :reproduce-blob from one of
                      the :failures returned by an earlier call to
                      test-fn!) which has Hegel run just that failure,
                      instead of searching and shrinking in general.

      :report-multiple-failures?
                      If set, Hegel will keep searching for additional distinct
                      failures after the first.

      :seed           A Long random seed

      :suppress-health-checks
                      A collection of health check keywords to
                      suppress: any of :test-cases-too-large, :filter-too-much,
                      :too-slow, :large-initial-test-case

      :test-cases     The number of test cases to run

      :verbosity      One of :debug, :normal, :quiet, or :verbose.

  Takes a function `(case-fn)` which will be invoked approximately `test-cases`
  times, with a TestCase argument. This case will also be bound to *test-case*
  for the duration of the function. This function can call `(draw gen)` to
  generate values. As a side effect, it should either return a value, or throw.

  Returns a map describing the results of the test, of the form:

      :passed?                Did all tests pass?
      :status                 Either :passed, :failed, or :error (if a health
                              check failed or the engine crashed)
      :statistics             A map of the :total, :valid, :invalid, :overrun,
                              and :interesting test case counts.
      :error                  A string error message, present iff the status
                              was :error.
      :flaky?                 Hegel thought this test was non-deterministic
      :health-check-failed?   Did a health check fail during the test?
      :failures               A sequence of failure maps which describe each
                              failing test case--see failure->map for details."
  [{:keys [database
           derandomize?
           health-checks
           name
           phases
           report-multiple-failures?
           seed
           suppress-health-checks
           test-cases
           verbosity
           ]}
   case-fn]
  (c/let [phases (when-not (nil? phases)
                 (mapv (fn [phase]
                       (case phase
                         :explicit Phase/EXPLICIT
                         :generate Phase/GENERATE
                         :reuse  Phase/REUSE
                         :shrink Phase/SHRINK
                         :target Phase/TARGET))
                     phases))

        health-checks
        (when-not (nil? health-checks)
          (mapv (fn [health-check]
                  (case health-check
                    :filter-too-much         HealthCheck/FILTER_TOO_MUCH
                    :large-initial-test-case HealthCheck/LARGE_INITIAL_TEST_CASE
                    :test-cases-too-large    HealthCheck/TEST_CASES_TOO_LARGE
                    :too-slow                HealthCheck/TOO_SLOW))
                health-checks))

        verbosity (case verbosity
                    :debug   Verbosity/DEBUG
                    :normal  Verbosity/NORMAL
                    :quiet   Verbosity/QUIET
                    :verbose Verbosity/VERBOSE
                    nil      nil)

        settings
        (cond-> (Settings.)
          database     (.database database)
          derandomize? (.derandomize derandomize?)
          name         (.name name)
          phases       (.phases (into-array Phase phases))

          (not (nil? report-multiple-failures?))
          (.reportMultipleFailures report-multiple-failures?)

          seed (.seed seed)
          suppress-health-checks
          (.suppressHealthCheck
            (into-array HealthCheck suppress-health-checks))

          test-cases (.testCases test-cases)
          verbosity  (.verbosity verbosity))
          reporter (Reporter/silent)
          report (Hegel/run
                (fn wrapper [test-case]
                  (binding [*test-case* test-case]
                    (case-fn test-case)))
                settings
                reporter)]
    (cond-> {:passed?  (.passed report)
             :status     (condp identical? (.status report)
                           RunStatus/PASSED :passed
                           RunStatus/FAILED :failed
                           RunStatus/ERROR :error)
             :statistics (c/let [statistics (.statistics report)]
                           {:total (.total statistics)
                            :valid (.valid statistics)
                            :invalid (.invalid statistics)
                            :overrun (.overrun statistics)
                            :interesting (.interesting statistics)})
             :health-check-failed? (.healthCheckFailed report)
             :failures (map failure->map (.failures report))}

      (identical? RunStatus/ERROR (.status report))
      (assoc :error (.orElse (.error report) nil))
    )))

(defmacro test!
  "Main entry point for running tests. This is the macro form of test-fn!;
  takes a body, rather than a function."
  [opts & body]
  `(test-fn! ~opts (bound-fn ~'case [~'_] ~@body)))

(defn run-stateful!
  "Within a test case, asks Hegel to run a stateful test over the given
  object. See hegel.dev.Stateful for details."
  ([state]
   (Stateful/run state *test-case*))
  ([test-case state]
   (Stateful/run state test-case)))

;; Working with test cases

(defn assume!
  "Rejects the current test case unless the given condition holds."
  ([condition]
   (.assume *test-case* condition))
  ([^TestCase test-case condition]
   (.assume test-case condition)))

(defn target!
  "Guides Hegel by reporting that something interesting has happened during
  this test case. Higher numbers are more interesting. Takes an optional label;
  either a string, or any object, which will be converted to a string with
  `pr-str`."
  ([^double value]
   (.target *test-case* value))
  ([^double value, label]
   (.target *test-case* value
            (if (string? label)
              label
              (pr-str label))))
  ([^TestCase test-case, ^double value, label]
   (.target test-case label
            (if (string? label)
              label
              (pr-str label)))))

(defn note!
  "Records a debug message which is shown only on the final replay of a failing
  case."
  ([msg]
   (.note *test-case* msg))
  ([^TestCase test-case msg]
   (.note test-case msg)))

;; Generating values

(defn draw!
  "Given a generator, draws a randomly selected value. Optionally takes a
  label, which will be used to describe the value in the final output. For
  example:

      (draw! (g/float))
      (draw! \"bools\" (g/vector (g/boolean)))
      (draw! test-case \"names\" (g/set (g/string)))"
  ([^Generator gen]
   (.draw *test-case* gen))
  ([label gen]
   (draw! *test-case* label gen))
  ([^TestCase test-case, label, ^Generator gen]
   (.draw test-case gen
          (if (string? label)
            label
            (pr-str label)))))

(defn ^long label
  "Takes a keyword and returns a long label for a span. These tell Hegel the
  kind of structure represented by the span. There are several built-in labels
  from Hegel.label. Using any other label will generate a fresh one."
  [label]
  (case label
    :list          Label/LIST
    :list-element  Label/LIST_ELEMENT
    :set           Label/SET
    :set-element   Label/SET_ELEMENT
    :map           Label/MAP
    :map-entry     Label/MAP_ENTRY
    :tuple         Label/TUPLE
    :one-of        Label/ONE_OF
    :optional      Label/OPTIONAL
    :fixed-dict    Label/FIXED_DICT
    :flat-map      Label/FLAT_MAP
    :filter        Label/FILTER
    :mapped        Label/MAPPED
    :sampled-from  Label/SAMPLED_FROM
    :enum-variant  Label/ENUM_VARIANT
    :stateful-rule Label/STATEFUL_RULE
    :composite     Label/COMPOSITE
    (Label/of (name label))))

(defmacro span!
  "Opens a span, evaluates body, then closes it. Spans denote a series of draws
  which belong together, so that Hegel can shrink them as a unit. Takes a
  literal keyword label, which can either be a built-in Hegel label or a custom
  one; see `label` for details.

  Can take an optional test case as the first argument, or just a span, in
  which case *test-case* is used.

      (span :list-element
        (draw! (g/integer)))

      (span my-test-case :list-element
        (draw! my-test-case (g/integer)))"
  [& args]
  (c/let [[test-case label-name body]
          (if (keyword? (first args))
            [`*test-case* (first args) (rest args)]
            [(first args) (second args) (drop 2 args)])
          ; We need a type hint
          test-case (vary-meta test-case assoc :tag `TestCase)
          label (label label-name)]
    `(try (.startSpan ~test-case label)
          ~@body
          (finally
            (.stopSpan ~test-case)))))

(defmacro let
  "Like Clojure's let, but when a right-hand side is a Generator, draws a value
  using hegel-clj.core/draw!. This lets you mix generators and regular values.
  For example:

      (h/let [a (gen/integer) ; Drawn randomly by hegel-clj
              b (+ a 2)]      ; Evaluated as normal
        ...)"
  [binding-forms & body]
  (assert (even? (count binding-forms)))
  (c/let [tmp-lhs (gensym 'lhs)]
    `(c/let [~@(mapcat (fn [[lhs rhs]]
                         ; We expand (let [a x] into
                         ; (let [lhs123 x
                         ;       a (if (instance? Generator lhs123)
                         ;            (draw! lhs123)
                         ;            lhs123)]
                         `[~tmp-lhs ~rhs
                           ~lhs (if (instance? Generator ~tmp-lhs)
                                  (draw! ~(pr-str lhs) ~tmp-lhs)
                                  ~tmp-lhs)])
                       (partition 2 binding-forms))]
      ~@body)))

(defn sample
  "Samples up to `n` values from the provided generator. Helpful for debugging
  generators. Can either take a number of values, or a map passed to `test!`,
  which is helpful if you want to pass a deterministic seed etc."
  [n-or-opts gen]
  (c/let [out (atom [])
        test-opts (if (integer? n-or-opts)
                    {:test-cases n-or-opts}
                    n-or-opts)]
    (test! test-opts
           (swap! out conj (draw! gen)))
    @out))

;; Final phase

(defn final?
  "Is Hegel in the final phase of a test?"
  ([]
   (.isFinal *test-case*))
  ([^TestCase test-case]
   (.isFinal test-case)))

(defn fprn
  "Wrapper for (when (final?) (prn ...))"
  [& args]
  (when (final?) (apply prn args)))

(defn fpp
  "Wrapper for (when (final?) (pprint x))"
  [x]
  (when (final?) (pprint x)))

(defmacro finfo
  "Wrapper for (when (final?) (info ...))"
  [& args]
  `(when (final?) (info ~@args)))

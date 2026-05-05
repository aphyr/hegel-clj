(ns hegel-clj.clojure-test-test
  (:require [clojure [pprint :refer [pprint]]
                     [test :refer :all]]
            [clojure.tools.logging :refer [info warn]]
            [hegel-clj [generator :as g]
                       [clojure-test :refer [with]]
                       [core :refer :all]])
  (:import (java.io StringWriter)))

; This namespace is a little weird. We're going to use clojure.test to test
; clojure.test, so we actually use (test-ns-hook) as our test entrypoint, and
; then invoke clojure.test *again* from there to run specific test cases and
; assert they print the right things.
;
; We name our sub-level tests `foo-test-`, and the wrapper test `foo-test`.

(defmacro capture
  "Evaluates body, presumably running clojure.test assertions. Captures stdout,
  stderr, and clojure.test reports to strings and a vector, respectively.
  Returns a map of :out, :err, :reports."
  [& body]
  `(let [out# (StringWriter.)
         err# (StringWriter.)
         reports# (atom [])
         report# (fn ~'report [event#]
                   (case (:type event#)
                     :begin-test-var nil
                     :end-test-var   nil
                     (swap! reports# conj event#)))]
     (binding [*out*               out#
               *err*               err#
               clojure.test/report report#]
       ~@body)
     {:out (str out#)
      :err (str err#)
      :reports @reports#}))

(deftest reverse-test*
  ; Generate roughly a hundred integers of vectors
  (with {:test-cases 10, :seed 1}
        [xs (g/vector (g/integer))]
    #_(prn :xs xs (if (= (sort xs) (reverse xs))
                    :pass
                    :fail))
    ; Print out only the smallest vectors that make this fail
    (fprn :final-xs xs)
    ; Is sorting the same as reversing? Clearly not, so this will fail,
    ; likely with a two-element vector of different numbers.
    (is (= (sort xs)
           (reverse xs)))))

(deftest reverse-test
  (let [{:keys [out err reports]} (capture (reverse-test*))]
    ; Note that we only tell clojure.test about the shrunk case.
    (is (= [[:fail [0 1] [[1 0]]]]
           (mapv (juxt :type :expected :actual) reports)))
    (is (= ":final-xs [0 1]\n" out))
    (is (= "" err))))

(defn test-ns-hook
  "Main entrypoint for tests. The other deftests don't get called directly; we
  run them in a little sandbox to make sure they fail, but fail in specific ways."
  []
  (reverse-test))

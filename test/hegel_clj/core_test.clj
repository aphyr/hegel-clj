(ns hegel-clj.core-test
  (:require [clojure [pprint :refer [pprint]]
                     [test :refer :all]]
            [clojure.tools.logging :refer [info warn]]
            [hegel-clj [core :refer :all]
                       [generator :as g]])
  (:import (dev.hegel Invariant
                      Rule
                      TestCase)))

(deftest test!-test
  (let [r (test! {:seed 1}
                (g/let [a (draw! (g/integer))
                        b (draw! (g/integer))]
                  (assert (= (+ a b) (+ a (min b 3))))))]
    (is (= {:passed? false,
            :status :failed
            :statistics {:total 55
                         :valid 12
                         :invalid 0
                         :overrun 22
                         :interesting 21}
            :health-check-failed? false}
           (dissoc r :failures)))
    (is (= 1 (count (:failures r))))
    (let [failure (first (:failures r))
          e (:exception failure)]
      (is (= {:caveat nil
              :reproduce-blob "AXicY2IAAi5GBgQFAAEFABk"
              :draws {"draw_1" 0
                      "draw_2" 4}})
          (dissoc failure :exception))
      (is (instance? AssertionError e))
      (is (= "Assert failed: (= (+ a b) (+ a (min b 3)))"
             (.getMessage e))))))

(definterface IntegerStack
  (push [^dev.hegel.TestCase tc])
  (pop [^dev.hegel.TestCase tc])
  (sizeIsNonNegative [^dev.hegel.TestCase tc]))

(deftype AIntegerStack [^:unsynchronized-mutable stack]
  IntegerStack
  (^{Rule true} push [this tc]
    (set! stack (conj stack (draw! (g/integer)))))
  (^{Rule true} pop [this tc]
    (assume! (seq stack))
    (set! stack (pop stack)))
  (^{Invariant true} sizeIsNonNegative [this tc]
    (assert (seq stack))))

(deftest stateful-integer-stack-test
  ; I'm not sure I understand this example; it feels like it should trivially
  ; fail because the invariant is false for the initial state.
  (let [r (test! {:seed 1}
                 (run-stateful! (AIntegerStack. [])))]
    ; (pprint r)
    (is (not (:passed? r)))
    (is (= {} (:draws (first (:failures r)))))))


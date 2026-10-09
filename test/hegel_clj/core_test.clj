(ns hegel-clj.core-test
  (:require [clojure [pprint :refer [pprint]]
                     [test :refer :all]]
            [clojure.tools.logging :refer [info warn]]
            [hegel-clj [core :as h]
                       [generator :as g]])
  (:import (dev.hegel Invariant
                      Rule
                      TestCase)))

(deftest test!-test
  (let [r (h/test! {:seed 1}
                   (h/let [a (g/integer)
                           b (g/integer)]
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
              :reproduce-blob "AXicY2IAAi5GBjjFAgABCQAd"
              :draws {"a" 0
                      "b" 4}}
          (dissoc failure :exception)))
      (is (instance? AssertionError e))
      (is (= "Assert failed: (= (+ a b) (+ a (min b 3)))"
             (.getMessage e))))))

(deftest final?-test
  ; A simple test which hunts for a vector which is not in reverse sorted
  ; order, and logs each attempted test case
  (let [log (atom [])
        r (h/test-fn! {:seed 1, :test-cases 10}
                      (fn [test-case]
                        (let [xs (h/draw! test-case "xs" (g/vector (g/integer)))
                              pass? (= (sort xs) (reverse xs))]
                          (swap! log conj {:final? (h/final? test-case)
                                           :xs xs
                                           :pass? pass?})
                          (assert pass?))))]
    ;(pprint r)
    ;(pprint @log)
    ;(pprint (frequencies (map :xs @log)))
    (is (= 1 (count (:failures r))))
    (let [finals (filter :final? @log)]'
      (is (= 1 (count finals)))
      (is (= [{:final true
               :xs [0 1]
               :pass? false}]
             finals)))))

#_(deftest span-test
  ; I don't actually know how to test this yet. We need to find some place
  ; where Hegel, I dunno, shrinks more efficiently given a span or something.
  (let [r (h/test! {:seed 1}
                   (h/span! :composite
                            (h/let [x (g/integer)
                                    y (g/integer)])))]
    (is (:passed? r))))

(definterface IntegerStack
  (push [^dev.hegel.TestCase tc])
  (pop [^dev.hegel.TestCase tc])
  (sizeIsNonNegative [^dev.hegel.TestCase tc]))

(deftype AIntegerStack [^:unsynchronized-mutable stack]
  IntegerStack
  (^{Rule true} push [this tc]
    (set! stack (conj stack (h/draw! tc (g/integer) "element"))))
  (^{Rule true} pop [this tc]
    (h/assume! (seq stack))
    (set! stack (pop stack)))
  (^{Invariant true} sizeIsNonNegative [this tc]
    (assert (seq stack))))

(deftest stateful-integer-stack-test
  ; I'm not sure I understand this example; it feels like it should trivially
  ; fail because the invariant is false for the initial state.
  (let [r (h/test! {:seed 1}
                   (h/run-stateful! (AIntegerStack. [])))]
    ; (pprint r)
    (is (not (:passed? r)))
    (is (= {} (:draws (first (:failures r)))))))


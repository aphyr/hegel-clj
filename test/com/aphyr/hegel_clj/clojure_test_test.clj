(ns com.aphyr.hegel-clj.clojure-test-test
  (:require [clojure [pprint :refer [pprint]]
                     [test :refer :all]]
            [clojure.tools.logging :refer [info warn]]
            [com.aphyr.hegel-clj [gen :as g]
                                 [clojure-test :refer [with]]
                                 [test :refer :all]]))

(deftest ^:focus reverse-test
  ; Generate roughly a hundred integers of vectors
  (with {:test-cases 10, :seed 1}
    [xs (g/vector (g/integer))]

    (prn :xs xs (if (= (sort xs) (reverse xs))
                  :pass
                  :fail))
    ; Print out only the smallest vectors that make this fail
    (fprn :final-xs xs)
    ; Is sorting the same as reversing? Clearly not, so this will fail,
    ; likely with a two-element vector of different numbers.
    (is (= (sort xs)
           (reverse xs)))))

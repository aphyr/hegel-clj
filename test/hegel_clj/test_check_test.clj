(ns hegel-clj.test-check-test
  "Compares hegel-clj to test.check."
  (:require [clojure [pprint :refer [pprint]]
                     [test :refer :all]]
            [clojure.test.check :as tc]
            [clojure.test.check [properties :as tcp]
                                [generators :as tcg]]
            [hegel-clj [core :as h]
                       [generator :as hg]]))

(deftest bind-shrink-test
  ; In test.check, one of the awkward things that can happen is that using one
  ; generated value to build another (e.g. with `bind`) can make it impossible
  ; to shrink well. See
  ; https://github.com/clojure/test.check/blob/master/doc/growth-and-shrinking.md#gotchas-1
  (let [; We're looking for an even-sized vector that has a 42 in it.
        tc-ints (tcg/let [size tcg/nat]
                  (tcg/vector tcg/large-integer size))
        tc-res (tc/quick-check 1000
                               (tcp/for-all [xs tc-ints]
                                            (not-any? #{42} xs))
                               {:seed 1777986545686})
        tc-smallest (-> tc-res :shrunk :smallest first)
        ; [0 0 0 ... 42 0]
        ;_ (prn tc-smallest)
        ; This is deeply silly
        _ (is (= {0 38, 42 1} (frequencies tc-smallest)))

        ; In Hegel-clj...
        h-ints (->> (hg/integer {:min 0, :max 128})
                    (hg/bind (fn [size]
                               (hg/vector {:min-size size, :max-size size}
                                          (hg/integer)))))
        h-res (h/run-test! {:test-cases 1000
                            :seed       1777986545686}
                           (hg/let [xs h-ints]
                             {:xs     xs
                              :status (if (not-any? #{42} xs)
                                        :valid
                                        :interesting)}))
        h-smallest (-> h-res :final first :xs)
        ; Ah, much better
        _ (is (= [42] h-smallest))]))

(ns com.aphyr.hegel-clj.gen-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :refer [info warn]]
            [com.aphyr.hegel-clj [test :refer :all]
                                 [gen :as g]])
  (:import (java.nio ByteBuffer)))

(deftest integer-schema-test
  (is (= {"type" "integer"}
         (g/schema->map (g/integer))))
  (is (= {"type" "integer"
          "min_value" 4
          "max_value" 7}
         (g/schema->map (g/integer {:min 4 :max 7})))))

(deftest one-of-test
  (run-test! {:test-cases 5}
             (g/let [x (g/one-of (g/boolean) (g/float))]
               (is (or (boolean? x) (float? x)))
               {:status :valid})))

(deftest boolean-test
  (run-test! {:test-cases 5}
             (is (boolean? (gen (g/boolean))))
             {:status :valid}))

(deftest integer-test
  (run-test! {:test-cases 5}
             (is (integer? (gen (g/integer))))
             (is (<= 4 (gen (g/integer {:min 4}))))
             (is (<= -6 (gen (g/integer {:min -6 :max -3})) -3))
             {:status :valid}))

(deftest float-test
  (run-test! {:test-cases 5}
             (is (float? (gen (g/float))))
             (is (<= 3.4 (gen (g/float {:min 3.4 :max 9.34})) 9.34))
             (is (< 3.4 (gen (g/float {:min 3.4 :max 9.34 :exclude-min? true :exclude-max? true})) 9.34))
             {:status :valid}))

(deftest string-test
  (run-test! {:test-cases 50}
             (is (string? (gen (g/string))))
             (g/let [^String s (g/string {:min-size 4 :max-size 6})]
               (is (string? s))
               (is (<= 4 (.codePointCount s 0 (.length s)) 6)))
             {:status :valid}))

(ns hegel-clj.core-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :refer [info warn]]
            [hegel-clj [core :refer :all]
                       [generator :as g]]))

(deftest run-test!-test
  (is (= {:health-check-failure? nil,
          :seed "1",
          :invalid-test-cases 0,
          :test-cases 11,
          :flaky? nil,
          :passed? false,
          :valid-test-cases 3,
          :error nil,
          :interesting-test-cases 1
          :final [{:status :interesting
                   :origin "bad+"
                   :foo :bar}]}
         (run-test! {:seed 1}
                    (g/let [a (gen (g/integer))
                            b (gen (g/integer))]
                      (if (not= (+ a b) (+ a (max b 3)))
                        {:status :interesting
                         :origin "bad+"
                         :foo :bar}
                        {:status :valid}))))))

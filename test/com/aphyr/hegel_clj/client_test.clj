(ns com.aphyr.hegel-clj.client-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :refer [info warn]]
            [com.aphyr.hegel-clj [client :refer :all]
                                 [gen :as g]])
  (:import (java.nio ByteBuffer)))

(deftest serde-test
  (let [payload (.. (ByteBuffer/allocate 3)
                    (put (unchecked-byte 0x12))
                    (put (unchecked-byte 0xaa))
                    (put (unchecked-byte 0xff))
                    (flip))
        packet (->RawPacket 7 -4 payload)]
    (is (= packet (-> packet raw-packet->buf buf->raw-packet)))))

(deftest turns-on-test
  (let [core (start-core!)]
    (is (.isAlive (:process core)))
    (stop-core! core)
    (not (.isAlive (:process core)))))

(deftest run-test-test
  (with-core core
    (is (= {:health-check-failure? nil,
            :seed "6",
            :invalid-test-cases 0,
            :test-cases 2,
            :flaky? nil,
            :passed? true,
            :valid-test-cases 2,
            :error nil,
            :interesting-test-cases 0
            :final []}
           (run-test! core {:test-cases 2
                            :seed 6}
                      (fn [stream-id]
                        (generate! core stream-id (g/integer))
                        {:status :valid}))))))

(deftest bad-add-test
  ; This is a bad version of addition which is only correct for addends up to
  ; three.
  (let [bad+ (fn [a b]
               (+ (min a 3) (min b 3)))
        test-results
        (with-core core
          (run-test! core {:test-cases 2
                           :seed "123"}
                     (fn [stream-id]
                       (let [a (generate! core stream-id (g/integer))
                             b (generate! core stream-id (g/integer))]
                         (if (= (+ a b) (bad+ a b))
                           {:status :valid}
                           {:status :interesting
                            :origin "bad+"
                            :foo :bar})))))]
    (is (= {:health-check-failure? nil,
            :seed "123",
            :invalid-test-cases 0,
            :test-cases 20,
            :flaky? nil,
            :passed? false,
            :valid-test-cases 8,
            :error nil,
            :interesting-test-cases 1
            :final [{:status :interesting, :origin "bad+", :foo :bar}]}
           test-results))))

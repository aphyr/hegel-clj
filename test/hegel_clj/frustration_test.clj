(ns hegel-clj.frustration-test
  "Tests for things that are frustrating me about Hegel, and a place to see if
  I can work around them."
  (:require [clojure [test :refer :all]
                     [pprint :refer [pprint]]]
            [hegel-clj [core :as h]
                       [generator :as g]]))

; Uncomment this later to file a bug with the hegel folks?
#_(deftest lots-of-stop-tests
  ; Hegel likes to send stop_test commands in the middle of a test case for no
  ; apparent reason. This makes it almost impossible to generate large, complex
  ; data structures.
  (let [; Let's say we wanted to generate a long list of operations. Each
        ; operation is an integer and a string--just two calls to `generate`
        ; here.
        gen-op (fn [_]
                 (g/let [a (g/integer)
                         b (g/string)
                         c (g/vector (g/integer))]
                   [a b c]))
        ; Now let's try to make a big collection of operations.
        gen-ops (fn []
                  (h/draw! (g/vector {:min-size 50, :max-size 1000}
                                     (g/composite-fn gen-op))))
        attempts (atom 0)
        counts (atom [])
        ; Try a thousand cases of that
        res (h/test! {:seed 1
                      :test-cases 1000}
                     (swap! attempts inc)
                     (let [ops (gen-ops)]
                       (swap! counts conj (count ops))))]
    ;(pprint res)
    ;(println @attempts "attempts," (count @counts) "completely generated histories")
    ;(pprint (into (sorted-map) (frequencies @counts)))))
    (is (:passed? res))
    ; We should have generated at least some long histories
    (is (< 500 (reduce max 0 @counts)))))

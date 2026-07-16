(ns hegel-clj.frustration-test
  "Tests for things that are frustrating me about Hegel, and a place to see if
  I can work around them."
  (:require [clojure [test :refer :all]
                     [pprint :refer [pprint]]]
            [hegel-clj [core :as h]
                       [generator :as g]]))

(deftest lots-of-stop-tests
  ; Hegel likes to send stop_test commands in the middle of a test case for no
  ; apparent reason. This makes it almost impossible to generate large, complex
  ; data structures.
  (let [; Let's say we wanted to generate a long list of operations. Each
        ; operation is an integer and a string--just two calls to `generate`
        ; here.
        gen-op (fn []
                 (g/let [a (g/integer)
                         b (g/string)]
                   [a b]))
        ; Now let's try to make a big collection like this, using Hegel's
        ; `new_collection`.
        gen-ops (fn []
                  (try
                    (g/collect [ops [] {:min-size 50, :max-size 1000}]
                               (conj ops (gen-op)))
                    (catch clojure.lang.ExceptionInfo e
                      (prn "Caught" (ex-data e))
                      (throw e))
                    (catch Throwable t
                      (prn "Caught" (class t) (.getMessage t)))))
        attempts (atom 0)
        counts (atom [])
        ; Try a thousand cases of that
        res (h/run-test! {:seed 1
                          :test-cases 1000}
                         (swap! attempts inc)
                         (let [ops (gen-ops)]
                           (swap! counts conj (count ops))
                           {:status :valid}))]
    (pprint res)
    (println @attempts "attempts," (count @counts) "completely generated histories")
    (pprint (into (sorted-map) (frequencies @counts)))))

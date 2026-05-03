(ns com.aphyr.hegel-clj.gen-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :refer [info warn]]
            [com.aphyr.hegel-clj [test :refer :all]
                                 [gen :as g]])
  (:import (java.nio ByteBuffer)))

(deftest integer-test
  (is (= {"type" "integer"}
         (g/schema->map (g/integer)))))

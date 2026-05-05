(ns com.aphyr.hegel-clj.gen-test
  (:require [clojure [pprint :refer [pprint]]
                     [test :refer [deftest is]]]
            [clojure.tools.logging :refer [info warn]]
            [com.aphyr.hegel-clj [clojure-test :refer [with]]
                                 [test :refer :all]
                                 [gen :as g]]
            [com.aphyr.hegel-clj.gen.proto :as gp])
  (:import (java.nio ByteBuffer)
           (java.net InetAddress
                     Inet4Address
                     Inet6Address)
           (java.time LocalDate
                      LocalDateTime
                      LocalTime)))

(deftest integer-schema-test
  (is (= {"type" "integer"}
         (gp/->map (g/integer))))
  (is (= {"type" "integer"
          "min_value" 4
          "max_value" 7}
         (gp/->map (g/integer {:min 4 :max 7})))))

(deftest one-of-test
  (with {:test-cases 5}
    [x (g/one-of (g/boolean) (g/float))]
    (is (or (boolean? x) (float? x)))

    (g/let [x (g/one-of (g/list (g/integer)) (g/set (g/integer)))]
      (is (or (list? x) (set? x)))
      (is (every? integer? x)))))

(deftest boolean-test
  (with {:test-cases 5} [g (g/boolean)]
    (is (boolean? g))))

(deftest integer-test
  (with {:test-cases 5} []
    (is (integer? (gen (g/integer))))
    (is (<= 4 (gen (g/integer {:min 4}))))
    (is (<= -6 (gen (g/integer {:min -6 :max -3})) -3))))

(deftest float-test
  (with {:test-cases 5} []
    (is (float? (gen (g/float))))
    (is (<= 3.4 (gen (g/float {:min 3.4 :max 9.34})) 9.34))
    (is (< 3.4 (gen (g/float {:min 3.4 :max 9.34 :exclude-min? true :exclude-max? true})) 9.34))))

(deftest string-test
  (with {:test-cases 50} []
    (is (string? (gen (g/string))))
    (g/let [^String s (g/string {:min-size 4 :max-size 6})]
      (is (string? s))
      (is (<= 4 (.codePointCount s 0 (.length s)) 6)))))

(deftest bytes-test
  (with {:test-cases 50} [b1 (g/bytes)
                          b2 (g/bytes {:min-size 5 :max-size 10})]
    (is (bytes? b1))
    (is (bytes? b2))
    (is (<= 5 10) (alength b2))))

(deftest regex-test
  (with {:test-cases 10} []
    (doseq [pattern [#"abc"
                     #"\w*"
                     #"rege(x(es)?|xps?)\Z"
                     #"^[^x]{2,5}x+$"
                     #"^\"/\\$"]]
      (g/let [s (g/regex pattern)]
        (is (re-find pattern s))))
    (g/let [s (g/regex #"[0-9]+" {:full-match? true})]
      (is (re-find #"^\d+$" s)))
    ; Digits are frustrating; Python \d generates all Unicode digits by
    ; default, which is *not* what you'd expect from Java.
    (g/let [s (g/regex "(?a)^\\d+$")]
      (is (re-find #"^\d+$" s)))))

(deftest vector-test
  (with {:test-cases 10}
        [any    (g/vector (g/one-of (g/float) (g/integer)))
         short  (g/vector {:max-size 5} (g/boolean))
         long   (g/vector {:min-size 3} (g/integer))
         unique (g/vector {:min-size 3, :max-size 7, :unique? true} (g/string))]
    (is (vector? any))
    (is (every? number? any))

    (is (vector? short))
    (is (every? boolean? short))
    (is (<= (count short) 5))

    (is (vector? long))
    (is (every? integer? long))
    (is (<= 3 (count long)))

    (is (vector? unique))
    (is (every? string? unique))
    (is (distinct? unique))))

(deftest list-test
  (with {:test-cases 10}
        [any    (g/list (g/one-of (g/float) (g/integer)))
         short  (g/list {:max-size 5} (g/boolean))
         long   (g/list {:min-size 3} (g/integer))
         unique (g/list {:min-size 3, :max-size 7, :unique? true} (g/string))]
        (is (list? any))
        (is (every? number? any))

        (is (list? short))
        (is (every? boolean? short))
        (is (<= (count short) 5))

        (is (list? long))
        (is (every? integer? long))
        (is (<= 3 (count long)))

        (is (list? unique))
        (is (every? string? unique))
        (is (distinct? unique))))

(deftest set-test
  (with {:test-cases 10}
        [any    (g/set (g/one-of (g/float) (g/integer)))
         short  (g/set {:max-size 5} (g/boolean))
         long   (g/set {:min-size 3} (g/integer))]
        (is (set? any))
        (is (every? number? any))

        (is (set? short))
        (is (every? boolean? short))
        (is (<= (count short) 5))

        (is (set? long))
        (is (every? integer? long))
        (is (<= 3 (count long)))))

(deftest map-test
  (with {:test-cases 10}
    [m     (g/map (g/integer) (g/set (g/float)))
     small (g/map {:max-size 6} (g/string) (g/boolean))
     large (g/map {:min-size 2} (g/integer) (g/set (g/boolean)))
     ]
    (is (map? m))
    (is (every? integer? (keys m)))
    (is (every? set? (vals m)))
    (is (every? float? (mapcat val m)))

    (is (map? small))
    (is (<= (count small) 6))
    (is (every? string? (keys small)))
    (is (every? boolean? (vals small)))

    (is (map? large))
    (is (<= 2 (count large)))
    (is (every? integer? (keys large)))
    (is (every? set? (vals large)))
    (is (every? boolean? (mapcat val large)))))

(deftest tuple-test
  (with {:test-cases 10}
    [t0 (g/tuple)
     t1 (g/tuple (g/boolean))
     t2 (g/tuple (g/boolean) (g/integer))
     t3 (g/tuple (g/boolean) (g/integer) (g/string))
     t4 (g/tuple (g/boolean) (g/integer) (g/string) (g/set (g/integer)))
     t5 (g/tuple (g/boolean) (g/integer) (g/string) (g/set (g/integer)) (g/list (g/vector (g/boolean))))]
    (is (vector? t0))
    (is (vector? t1))
    (is (vector? t2))
    (is (vector? t3))
    (is (vector? t4))
    (is (vector? t5))

    (is (boolean? (t1 0)))
    (is (boolean? (t2 0)))
    (is (boolean? (t3 0)))
    (is (boolean? (t4 0)))
    (is (boolean? (t5 0)))

    (is (integer? (t2 1)))
    (is (integer? (t3 1)))
    (is (integer? (t4 1)))
    (is (integer? (t5 1)))

    (is (string? (t3 2)))
    (is (string? (t4 2)))
    (is (string? (t5 2)))

    (is (set? (t4 3)))
    (is (set? (t5 3)))
    (is (every? integer? (t4 3)))
    (is (every? integer? (t5 3)))

    (is (list? (t5 4)))
    (is (every? vector? (t5 4)))
    (is (every? boolean? (mapcat identity (t5 4))))))

(deftest email-test
  (with {:test-cases 5} [e (g/email)]
    (is (re-find #"@" e))))

(deftest url-test
  (with {:test-cases 5} [u (g/url-str)]
    (is (re-find #"://" u))))

(deftest domain-test
  (with {:test-cases 5}
    [d (g/domain)]
    (is (re-find #"\." d))))

(defn ipv4?
  [addr]
  (instance? Inet4Address (InetAddress/getByName addr)))

(defn ipv6?
  [addr]
  (fprn (InetAddress/getByName addr))
  (or (instance? Inet6Address (InetAddress/getByName addr))
      ; Note that ::ffff:0:0 get mapped to v4 0.0.0.0. Thanks Java.
      (re-find #"^::ffff:" addr)))

(deftest ip-address-test
  (with {:test-cases 10}
    [a  (g/ip-address-str)
     a4 (g/ip-address-str 4)
     a6 (g/ip-address-str 6)]
    (fprn a6)
    (is (or (ipv4? a) (ipv6? a)))
    (is (ipv4? a4))
    (is (ipv6? a6))))

(deftest datetime-str-test
  (with {:test-cases 10}
    [d  (g/local-date-str)
     t  (g/local-time-str)
     dt (g/local-date-time-str)]
    (is (re-find #"^[\d-]+$" d))
    (is (re-find #"^[\d:\.]+$" t))
    (is (re-find #"^[\d-]+T[\d:\.]+$" dt))))

(deftest local-date-time-test
  (with {:test-cases 10}
    [d  (g/local-date)
     t  (g/local-time)
     dt (g/local-date-time)]
    (is (instance? LocalDate d))
    (is (instance? LocalTime t))
    (is (instance? LocalDateTime dt))))

(deftest bind-test
  (let [s (g/bind (fn [size]
                    (g/tuple (g/vector {:size size} (g/integer))
                             (g/vector {:size size} (g/float))))
                  (g/integer {:min 1, :max 5}))]
    (with {:test-cases 10}
          [[ints floats] s]
          (is (pos? (count ints)))
          (is (= (count ints) (count floats)))
          (is (every? integer? ints))
          (is (every? float? floats))
          )))

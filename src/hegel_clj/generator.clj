(ns hegel-clj.generator
  "Composable generators. These functions return a *schema*, which can be used
  with hegel-clj.test/gen to produce an actual value. We generally follow
  Hegel's protocol, but with a Clojure flavor: we use `vec` instead of `list`,
  because it generates vectors, and `bytes` instead of `binary`, and so on.
  Option names use :kebab-case keywords and the usual :flag? for boolean
  options."
  (:refer-clojure :exclude [boolean
                            bytes
                            double
                            float
                            let
                            list
                            long
                            map
                            set
                            vector
                            shuffle
                            symbol
                            keyword
                            sorted-map
                            sorted-set])
  (:require [clojure [core :as c]
                     [walk :refer [prewalk]]]
            [clojure.tools.logging :refer [info warn]]
            [hegel-clj [core :as h]])
  (:import (clojure.lang PersistentList)
           (dev.hegel Generator
                      Generators
                      Tuple2
                      Tuple3
                      Tuple4
                      Tuple5
                      Tuple6
                      Tuple7
                      Tuple8)
           (dev.hegel.generators Deferred)
           (java.time LocalDate
                      LocalDateTime
                      LocalTime)
           (java.time.format DateTimeFormatter)))

(defn fmap
  "Transforms generated values. Takes a function which takes one generated
  value to another, and a Generator. Returns a Generator which applies f to
  those values. For example, to generate even integers:

      (fmap (partial * 2) (integers))

  Hegel calls this `map`."
  [f ^Generator gen]
  (.map gen f))

(defn bind
  "Transforms generators. Takes a function (f x) -> gen, and a generator of xs.
  Produces a generator which uses `gen` to generate an x, calls (f x), and
  draws a value from the resulting generator.

  For example, to build a generator which generates two vectors wth the
  same size, one with ints, the other with floats:

      (bind (fn [size]
              (tuple (vector {:size size} (integers))
                     (vector {:size size} (floats))))
            (integers))

  Hegel calls this `flatMap`."
  [f ^Generator gen]
  (.flatMap gen f))

(defn filter
  "Filters a generator to produce only values which pass (f x)."
  [f ^Generator gen]
  (.filter f gen))

(defn composite-fn
  "A generator which builds a value by making imperative draws from a test
  case. Takes a function `(f test-case) -> x`, and returns a Generator that
  returns xs."
  [f]
  (Generators/composite f))

(defn deferred
  "Like a promise for Generators. Constructs a forward reference for building
  self-recursive or mutually-recursive generators. Use `(set-deferred! deferred
  some-gen)` to provide the generator later."
  []
  (Generators/deferred))

(defn set-deferred!
  "Sets the value of a Deferred generator to the provided gen. Returns the
  deferred."
  [^Deferred d gen]
  (.set d gen)
  d)

(defn just
  "A Generator which always returns the provided object."
  [x]
  (Generators/just x))

(defn one-of
  "A generator which returns a value drawn from one of several generators."
  [gens]
  (Generators/oneOf (into-array Generator gens)))

(defn sampled-from
  "A generator which returns one of several values."
  [xs]
  (Generators/sampledFrom xs))

(defn boolean
  "Generates a boolean."
  []
  (Generators/booleans))

(defn integer
  "Generates a 32-bit Integer. Options:

      :min    Minimum value, inclusive
      :max    Minimum value, inclusive"
  ([]
   (Generators/integers))
  ([{:keys [min max]}]
   (cond-> (Generators/integers)
     min (.min min)
     max (.max max)))
  ([min max]
   (integer {:min min, :max max})))

(defn long
  "Generates a 64-bit Long. Options:

      :min    Minimum value, inclusive
      :max    Minimum value, inclusive"
  ([]
   (Generators/longs))
  ([{:keys [min max]}]
   (cond-> (Generators/longs)
     min (.min min)
     max (.max max)))
  ([min max]
   (long {:min min, :max max})))

(defn float
  "Generates a 32-bit Float. Options:

      :min          Minimum value
      :max          Maximum value (ditto)
      :exclude-min? Exclude the minimum value
      :exclude-max? Exclude the maximum value
      :nan?         Whether to allow NaNs
      :infinity?    Whether to allow infinity
      :width        Bit width: 32 or 64"
  ([]
   (Generators/floats))
  ([{:keys [infinity? nan? min max exclude-min? exclude-max?]}]
   (cond-> (Generators/floats)
     (not (nil? infinity?))     (.allowInfinity infinity?)
     (not (nil? nan?))          (.allowNan nan?)
     (not (nil? exclude-min?))  (.excludeMin exclude-min?)
     (not (nil? exclude-max?))  (.excludeMin exclude-max?)
     min                        (.min min)
     max                        (.max max)))
  ([min max]
   (float {:min min, :max max})))

(defn double
  "Generates a 64-bit Double. Options:

      :min          Minimum value
      :max          Maximum value (ditto)
      :exclude-min? Exclude the minimum value
      :exclude-max? Exclude the maximum value
      :nan?         Whether to allow NaNs
      :infinity?    Whether to allow infinity
      :width        Bit width: 32 or 64"
  ([]
   (Generators/doubles))
  ([{:keys [infinity? nan? min max exclude-min? exclude-max?]}]
   (cond-> (Generators/doubles)
     (not (nil? infinity?))     (.allowInfinity infinity?)
     (not (nil? nan?))          (.allowNan nan?)
     (not (nil? exclude-min?))  (.excludeMin exclude-min?)
     (not (nil? exclude-max?))  (.excludeMin exclude-max?)
     min                        (.min min)
     max                        (.max max)))
  ([min max]
   (double {:min min, :max max})))

(defn string
  "Generates a Unicode string. Options:

  :min-size     Minimum size, in code points
  :max-size     Maximum size, in code points

  TODO: fill in other options from
  https://javadoc.io/static/dev.hegel/hegel/0.4.0/dev.hegel/dev/hegel/generators/TextGenerator.html"
  ([]
   (Generators/text))
  ([{:keys [min-size max-size]}]
   (cond-> (Generators/text)
     min-size (.minSize min-size)
     max-size (.maxSize max-size))))

(defn bytes
  "Generates a byte array. Hegel calls this `bytes`. Options:

  :min-size     Minimum size
  :max-size     Maximum size"
 ([]
  (Generators/binary))
 ([{:keys [min-size max-size]}]
  (cond-> (Generators/binary)
    min-size (.minSize min-size)
    max-size (.maxSize max-size))))

(defn regex-str
  "Generator of strings matching the given regular expression, which can be
  either a regex or string. The regex language used to be Python; with the Rust
  rewrite I'm not sure what it is.

  It used to be that #\"^\\d$\" would produce digits like \"߄\". The Python
  syntax for enabling ASCII mode was illegal in Java patterns, so you had to
  fall back to [0-9] or a string if you want to generate the usual ASCII
  digits:

    (regex \"(?a)^\\d+$\") ; => 5, 2, 0, 3, ...

  Options:

    :full-match?    Whether the pattern must match the full string, or if a
                    substring match is allowed. Default false."
  ([pattern]
   (Generators/fromRegex (str pattern)))
  ([pattern {:keys [full-match?]}]
   (cond-> (Generators/fromRegex (str pattern))
     (not (nil? full-match?)) (.fullmatch (c/boolean full-match?)))))

(defn vector
  "Generates a vector of elements. Takes options and Generator of elements.

    :size       Shorthand for {:min-size size, :max-size size}
    :min-size   The minimum number of elements, inclusive
    :max-size   The maximum number of elements, inclusive
    :unique?    If set, picks unique elements"
  ([elements]
   (fmap vec (Generators/lists elements)))
  ([{:keys [size min-size max-size unique?]} elements]
   (c/let [min-size (or min-size size)
           max-size (or max-size size)]
     (fmap vec (cond-> (Generators/lists elements)
                 min-size (.minSize min-size)
                 max-size (.maxSize max-size))))))

(defn list
  "Like vector, but returns Clojure lists."
  ([elements]
   (list {} elements))
  ([opts elements]
   (fmap (fn fmap [xs]
           (PersistentList/create xs))
         (vector opts elements))))

(defn set
  "Generates sets of elements. Options:

    :size       Shorthand for {:min-size size, :max-size size}
    :min-size   The minimum number of elements, inclusive
    :max-size   The maximum number of elements, inclusive"
  ([elements]
   (set {} elements))
  ([{:keys [min-size max-size]} elements]
   (fmap c/set (cond-> (Generators/sets elements)
                 min-size (.minSize min-size)
                 max-size (.maxSize max-size)))))

(defn sorted-set
  "Like `set`, but generates sorted sets."
  ([elements]
   (sorted-set nil elements))
  ([opts elements]
   (fmap (partial into (c/sorted-set))
         (set opts elements))))

(defn map
  "Generates a map of keys to values, given a generator for keys and another
  for values. Options:

    :size       Shorthand for {:min-size size, :max-size size}
    :min-size   The minimum number of elements, inclusive
    :max-size   The maximum number of elements, inclusive

  Note that Hegel does not seem to be able to generate keys with some
  types--you can't, for instance, generate keys which are vectors of integers."
  ([keys values]
   (fmap (partial into {}) (Generators/maps keys values)))
  ([{:keys [size min-size max-size]} keys values]
   (c/let [min-size (or min-size size)
           max-size (or max-size size)]
     (fmap (partial into {})
           (cond-> (Generators/maps keys values)
             min-size (.minSize min-size)
             max-size (.maxSize max-size))))))

(defn sorted-map
  "Like `map`, but generates sorted maps."
  ([keys values]
   (sorted-map nil keys values))
  ([opts keys values]
   (fmap (partial into (c/sorted-map))
         (map opts keys values))))

(defn tuple*
  "Takes a vector of generators and returns a generator of vectors whose first
  element is drawn from the first generator, second element from the second,
  and so on."
  [gens]
  (fmap vec (Generators/tuples (into-array Generator gens))))

(defn tuple
  "Takes any number of generators and generates a fixed-size vector of that
  size, where each element is drawn from the corresponding generator."
  ([] (just []))
  ([a] (fmap c/vector a))
  ([a b]
   (fmap (fn untuple2 [^Tuple2 t]
           [(.value1 t) (.value2 t)])
         (Generators/tuples a b)))
  ([a b c]
   (fmap (fn untuple3 [^Tuple3 t]
           [(.value1 t)
            (.value2 t)
            (.value3 t)])
         (Generators/tuples a b c)))
  ([a b c d]
   (fmap (fn untuple4 [^Tuple4 t]
           [(.value1 t)
            (.value2 t)
            (.value3 t)
            (.value4 t)])
         (Generators/tuples a b c d)))
  ([a b c d e]
   (fmap (fn untuple5 [^Tuple5 t]
           [(.value1 t)
            (.value2 t)
            (.value3 t)
            (.value4 t)
            (.value5 t)])
         (Generators/tuples a b c d e)))
  ([a b c d e f]
   (fmap (fn untuple6 [^Tuple6 t]
           [(.value1 t)
            (.value2 t)
            (.value3 t)
            (.value4 t)
            (.value5 t)
            (.value6 t)])
         (Generators/tuples a b c d e f)))
  ([a b c d e f g]
   (fmap (fn untuple7 [^Tuple7 t]
           [(.value1 t)
            (.value2 t)
            (.value3 t)
            (.value4 t)
            (.value5 t)
            (.value6 t)
            (.value7 t)])
         (Generators/tuples a b c d e f g)))
  ([a b c d e f g h]
   (fmap (fn untuple8 [^Tuple8 t]
           [(.value1 t)
            (.value2 t)
            (.value3 t)
            (.value4 t)
            (.value5 t)
            (.value6 t)
            (.value7 t)
            (.value8 t)])
         (Generators/tuples a b c d e f g h)))
  ([a b c d e f g h & more]
   (->> (into [a b c d e f g] more)
        (into-array Generator)
        Generators/tuples
        (fmap vec))))

(defn hmap
  "Generates a heterogenous map. Takes a map of keys (any objects) to
  generators, and returns a generator which produces maps with those keys, and
  values drawn from their corresponding generators. For example:

      (g/hmap {:name (g/string)
               :age  (g/integer)})"
  [m]
  (c/let [ks (keys m)]
    (fmap (fn post [vs]
              (zipmap ks vs))
            (tuple* (vals m)))))

(defn email
  "Generates an email address as a string, per RFC 5322 section 3.4.1"
  []
  (Generators/emails))

(defn url-str
  "Generates a URL as a string, per RFC 3986."
  []
  (Generators/urls))

(defn domain
  "Generates a domain name as a string, per RFC 1035."
  []
  (Generators/domains))

(defn ip-address-str
  "Generates an IP address; version should be either `4` for ipv4, or `6` for
  ipv6. With no version, generates both kinds."
  ([]
   (Generators/ipAddresses))
  ([version]
   (case version
     4 (.v4 (Generators/ipAddresses))
     6 (.v6 (Generators/ipAddresses)))))

(defn local-date
  "Generator of java.time.LocalDate."
  []
  (Generators/dates))

(defn local-date-str
  "Generates an ISO 8601 date string, like 1987-02-16"
  []
  (fmap (fn format [^LocalDate d]
          (.format d DateTimeFormatter/ISO_LOCAL_DATE))
        (local-date)))

(defn local-time
  "Generates a java.time.LocalTime."
  []
  (Generators/times))

(defn local-time-str
  "Generates an ISO 8601 time string, like 14:30:00.123"
  []
  (fmap (fn format [^LocalTime t]
          (.format t DateTimeFormatter/ISO_LOCAL_TIME))
        (local-time)))

(defn local-date-time
  "Generates a java.time.LocalDateTime."
  []
  (Generators/datetimes))

(defn local-date-time-str
  "Generates an ISO 8601 datetime string, like 2024-03-15T14:30:00"
  []
  (fmap (fn format [^LocalDateTime dt]
          (.format dt DateTimeFormatter/ISO_LOCAL_DATE_TIME))
        (local-date-time)))

(def clj-sym-initial-pattern
  "A partial regular expression pattern for the basic characters that can start
  a Clojure symbol"
  "A-Za-z_+\\-*=!$%&")

(def clj-sym-basic-pattern
  "A partial regular expression pattern for the basic characters that can make
  up a Clojure symbol."
  (str "[" clj-sym-initial-pattern "]"
       "[0-9#'" clj-sym-initial-pattern "]*"))

(defn simple-symbol
  "A unqualified Clojure symbol, e.g. 'foo"
  []
  (fmap c/symbol
        (regex-str (str "\\A" clj-sym-basic-pattern "\\Z"))))

(defn qualified-symbol
  "A qualified Clojure symbol, e.g. 'foo/bar"
  []
  (fmap c/symbol
        (regex-str (str "\\A" clj-sym-basic-pattern "/"
                        clj-sym-basic-pattern "\\Z"))))

(defn symbol
  "Generates a Clojure symbol, either simple or qualified."
  []
  (one-of [(simple-symbol) (qualified-symbol)]))

(defn simple-keyword
  "An unqualified Clojure keyword, e.g. :foo"
  []
  (fmap c/keyword (simple-symbol)))

(defn qualified-keyword
  "An unqualified Clojure keyword, e.g. :foo/bar"
  []
  (fmap c/keyword (qualified-symbol)))

(defn keyword
  "A Clojure keyword, either simple or qualified."
  []
  (fmap c/keyword (symbol)))

(defmacro let
  "Like Clojure's let, but when a right-hand side is a Generator, draws a value
  using hegel-clj.core/draw!. This lets you mix generators and regular values.
  For example:

      (gen/let [a (gen/integer) ; Drawn randomly by hegel-clj
                b (+ a 2)]      ; Evaluated as normal
        ...)"
  [binding-forms & body]
  (assert (even? (count binding-forms)))
  (c/let [tmp-lhs (gensym 'lhs)]
    `(c/let [~@(mapcat (fn [[lhs rhs]]
                         ; We expand (let [a x] into
                         ; (let [lhs123 x
                         ;       a (if (instance? Generator lhs123)
                         ;            (hegel-clj.core/draw! lhs123)
                         ;            lhs123)]
                         `[~tmp-lhs ~rhs
                           ~lhs (if (instance? Generator ~tmp-lhs)
                                  (hegel-clj.core/draw! ~tmp-lhs
                                                        ~(pr-str lhs))
                                  ~tmp-lhs)])
                       (partition 2 binding-forms))]
       ~@body)))

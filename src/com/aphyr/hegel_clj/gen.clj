(ns com.aphyr.hegel-clj.gen
  "Composable generators. These functions return a *schema*, which can be used
  with hegel-clj.test/gen to produce an actual value. These are named after,
  and use the same option names, as the Hegel protocol, with a few
  Clojure-style tweaks: kebab-case names, keyword arguments, and some helper
  arities.

  See https://hegel.dev/reference/protocol#schemas for details."
  (:refer-clojure :exclude [boolean let])
  (:require [clojure.core :as c]))

; We *could* represent schemas as maps, but having a special datatype lets us
; do (gen/let [x 1, y (gen/integer)].
(defrecord Schema [type])

(defn schema->map
  "Converts a Schema to a plain map, for CBOR serialization."
  [^Schema s]
  (assoc (.__extmap s) "type" (.type s)))

(defn schema*
  "Constructs an instance of Schema. Takes a type and a map of options which
  will be passed to hegel core."
  [type m]
  ; Defrecords have a constructor which takes fields, meta, and an extmap
  (Schema. type nil m))

(defmacro schema
  "Helper macro for constructing schemas. Takes a type string, a Hegel options
  map (e.g. `{\"value\" 2}`, a Clojure options map (e.g. `{:min 2}`, and a flat
  series of `clojure-name hegel-name` pairs where clojure-name is a keyword and
  hegel-name is a string. Expands into code which constructs an instance of
  Schema, merging into the Hegel options map options taken from the CLojure
  options map, rewriting keys using rewrite-pairs."
  [type hegel-opts clj-opts & rewrite-pairs]
  (assert (even? (count rewrite-pairs)))
  (c/let [map-sym (gensym 'm)]
    `(c/let [~map-sym (transient ~hegel-opts)
           ~@(mapcat (fn [[clojure-name hegel-name]]
                       `[~map-sym
                         (c/let [v# (get ~map-sym ~clojure-name ::not-found)]
                           (if (identical? ::not-found v#)
                             ~map-sym
                             (assoc! ~map-sym ~hegel-name v#)))])
                     (partition 2 rewrite-pairs))]
       (schema* ~type (persistent! ~map-sym)))))

(defn constant
  "Always generates `x`."
  [x]
  (schema* "constant" {"value" x}))

(defn one-of
  "A value drawn from one of several generators. Returns `[index, value]`
  pairs, where `index` is the index of the generator which was used, and
  `value` is the value it produced."
  [& gens]
  (schema* "one_of" {"generators" gens}))

(defn boolean
  "Generates a boolean."
  []
  (schema* "boolean" {}))

(defn integer
  "Generates an integer. Options:

      :min    Minimum value, inclusive
      :max    Minimum value, inclusive"
  ([]
   (schema* "integer" {}))
  ([{:keys [min max]}]
   (schema "integer" {} opts
            :min "min_value"
            :max "max_value")))

(defmacro let
  "Like Clojure's let, but when a right-hand side is a generator, draws a value
  using hegel-clj.test/gen. This lets you mix generators and regular values.
  For example:

      (gen/let [a (gen/integer) ; Drawn randomly by hegel-clj
                b (+ a 2)]      ; Evaluated as normal
        ...)"
  [binding-forms & body]
  (assert (even? (count binding-forms)))
  `(c/let [~@(mapcat (fn [[lhs rhs]]
                       ; We expand (let [a x] into
                       ; (let [a x
                       ;       a (if (instance? Schema a)
                       ;            (hegel-clj.test/gen a)
                       ;            a)]
                       `[~lhs ~rhs
                         ~lhs (if (instance? Schema ~lhs)
                                (com.aphyr.hegel-clj.test/gen ~lhs)
                                ~lhs)])
                     (partition 2 binding-forms))]
     ~@body))

# Hegel-clj

[![Clojars Project](https://img.shields.io/clojars/v/com.aphyr/hegel-clj.svg?include_prereleases)](https://clojars.org/com.aphyr/hegel-clj)
[![cljdoc badge](https://cljdoc.org/badge/com.aphyr/hegel-clj)](https://cljdoc.org/d/com.aphyr/hegel-cljp)

This library provides Clojure bindings for the [Hegel property-based testing
library](https://hegel.dev/). Like
[test.check](https://github.com/clojure/test.check),
[Quickcheck](https://www.erlang-factory.com/upload/presentations/19/quickchecktutorial_tartsjhughes.pdf),
and [Hypothesis](https://hypothesis.works), Hegel lets you write generators of
random values, a property over those values, searches for values which would
make that property fail. Hegel-clj wraps
[hegel-java](https://github.com/hegel-dev/hegel-java), and supports the full
range of Hegel generators, including imperative and functional composition and
recursion. It often shrinks better than `test.check`, integrates nicely with
`clojure.test`, and can log only during the minimal test cases.

## Quickstart

Imagine you had a function that found the index of a value in a vector:

```clj
(ns my-test)

(defn fast-index-of
  "Finds the index of element `x` in collection `xs`, or -1."
  [xs x]
  (loop [i 0]
    (if (= i (count xs))
      -1
      (if (identical? x (nth xs i))
        i
        (recur (inc i))))))

(fast-index-of [1 2 3] 2)
; => 1
```

Now let's write a generative test for this function, using hegel-clj:

```clj
(ns my-test
  (:require [clojure.test :refer :all]
            [hegel-clj [core :as h]
                       [clojure-test :refer [with]]
                       [generator :as g]]))

(deftest fast-index-of-test
  (with {:test-cases 100}
        [x  (g/integer)
         xs (g/vector (g/integer))]
    (prn :x x, :xs xs)
    (is (= (.indexOf xs x) (fast-index-of xs x)))))
```

When we run this test, Hegel runs the body of the `with` expression roughly a
hundred times with randomly generated integers for `x` and `xs`. If the `(is
...)` assertion fails, it searches for a simpler choice of `x` and `xs` that
still cause a bug---a process called *shrinking*.

```clj
(fast-index-of-test)

:x 0 :xs []
:x -8191 :xs [-1826]
:x 389806 :xs [-447516231 -15128098]
:x -6584392 :xs [26819 10606 -447617 -2 675261 8563716 463915 416 994369130 1718 -3710 -45075246 -1808406607 30330 214669 2048 -80798029 304776 27207 755618 108172]
:x 121113 :xs [-3721 -6111912 -932 1444339 173 -131071 -496 8192 19937]
:x -226 :xs [266 9205293 -10993 12258709 4096 2691019 2079058 -1281 338]
:x -268435457 :xs [29 1266]
:x 2147483647 :xs [2891 55981610 52406 53515]
:x 3 :xs [1217891853 -858473924 923 202 2047 2147483646 49472 2688 16 2147483647 -494391 -3153390]
:x -6 :xs [56911 -127 4096 -852854832]
:x -852854832 :xs [56911 -127 4096 -852854832]
:x -852854832 :xs [56911 -127 4096 -852854832]

FAIL in (fast-index-of-test) (form-init17666097679615558382.clj:6)
expected: (= (.indexOf xs x) (fast-index-of xs x))
  actual: (not (= 3 -1))

...

FAIL in (fast-index-of-test) (form-init17666097679615558382.clj:6)
expected: (= (.indexOf xs x) (fast-index-of xs x))
  actual: (not (= 3 -1))
:x -852854832 :xs [56911 -127 4096 -852854832]
:x -852854832 :xs [-127 4096 -852854832]
:x -852854832 :xs [-127 -852854832]
:x -852854832 :xs [-852854832]
:x -852854832 :xs []
:x 0 :xs [0]
:x -426427416 :xs [-426427416]
...
:x 128 :xs []
:x 128 :xs []
:x 128 :xs [128]

FAIL in (fast-index-of-test) (form-init17666097679615558382.clj:6)
expected: (= (.indexOf xs x) (fast-index-of xs x))
  actual: (not (= 0 -1))
```

Hegel-clj found a case where the index of an element should have been `0`, but
our `fast-index-of` function returned `-1`. You can see each version of `x` and
`xs` Hegel tried, and how once it discovered a bug, it tried a variety of
smaller numbers and vectors to try and reproduce the problem.

Rather than see *every* value, we can log just those from Hegel's *final
phase*, where it replays the smallest failing example it found. Due to a what I
think is a bug in Hegel-java, this runs more times than it ought to, but it's
still helpful:

```clj
(deftest fast-index-of-test
  (with {:test-cases 100}
        [x  (g/integer)
         xs (g/vector (g/integer))]
    (h/fprn :x x, :xs xs)
    (is (= (.indexOf xs x) (fast-index-of xs x)))))
```

We've changed `prn` to `fprn` ("final prn"), which runs only in the final
phase. Now we only have to read the output from the smallest failing test case:

```clj
(fast-index-of-test)

:x -4236 :xs [-4236 -317125]

FAIL in (fast-index-of-test) (form-init17666097679615558382.clj:6)
expected: (= (.indexOf xs x) (fast-index-of xs x))
  actual: (not (= 0 -1))
:x -4236 :xs [-4236 -317125]

FAIL in (fast-index-of-test) (form-init17666097679615558382.clj:6)
expected: (= (.indexOf xs x) (fast-index-of xs x))
  actual: (not (= 0 -1))
:x -4236 :xs [-4236 -317125]

FAIL in (fast-index-of-test) (form-init17666097679615558382.clj:6)
expected: (= (.indexOf xs x) (fast-index-of xs x))
  actual: (not (= 0 -1))
:x -4236 :xs [-4236 -317125]

FAIL in (fast-index-of-test) (form-init17666097679615558382.clj:6)
expected: (= (.indexOf xs x) (fast-index-of xs x))
  actual: (not (= 0 -1))
:x 128 :xs [128]

FAIL in (fast-index-of-test) (form-init17666097679615558382.clj:6)
expected: (= (.indexOf xs x) (fast-index-of xs x))
  actual: (not (= 0 -1))
```

Bingo. Our function fails when given a vector `[128]`. Let's ask some more
questions about those bad inputs...

```clj
(deftest fast-index-of-test
  (with {:test-cases 100}
        [x  (g/integer)
         xs (g/vector (g/integer))]
    (when (h/final?)
      (let [x0 (xs 0)]
        (prn :x (class x) x, :x0 (class x0) x0)
        (prn 'identical? (identical? x x0))
        (prn '= (= x x0))))
    (is (= (.indexOf xs x) (fast-index-of xs x)))))
```

The `(when (h/final?) ...)` valuates its body only in the final test phase, so
we can play around without producing a zillion log lines.

```clj
...
:x java.lang.Integer 128 :x0 java.lang.Integer 128
identical? false
= true

FAIL in (fast-index-of-test) (form-init17666097679615558382.clj:10)
expected: (= (.indexOf xs x) (fast-index-of xs x))
  actual: (not (= 0 -1))
nil
```

Aha! So these are both `java.lang.Integers`, and while they're *equal*
(represent the same value) they're not *identical* (at the same memory
address). Integers up to 127 are identical on OpenJDK, so we wouldn't have
caught this bug if we stuck to small numbers.

## Overview

- [`hegel-clj.core`](/src/hegel_clj/core.clj) provides a friendly API to Hegel-clj, including dynamic state.
- [`hegel.generator`](/src/hegel_clj/generator.clj) constructs generators of random values.
- [`hegel.clojure-test`](/src/hegel_clj/clojure_test.clj) provides `clojure.test` integration.

Your main entrypoint is generally either through a test integration namespace
(like `hegel-clj.clojure-test/with`), or by starting a test using
`hegel-clj.core/test!`. From there, you can generate random values using
`hegel-clj.core`'s `let` or `draw!`. `hegel-clj.core/test!` will hunt for
values that cause the body to throw an exception, and
`hegel-clj.clojure-test/with` will also flag any `clojure.test/is` failures.

## Philosophy

Hegel takes an imperative approach to testing. Generated values are
deterministic (and Hegel will warn you when your tests aren't!), but the
generators have implicit side effects---notably, each call to `draw!` mutates
the Hegel PRNG. This is actually sort of nice: it frees you to generate values
anywhere, just like you would with `(rand)`, and work with them incrementally.

We lean into that imperative style by making it easy to do `prn`-style
debugging, but only during the final test phase.

## Generators

All generators live in [`hegel-clj.generator`](src/hegel_clj/generator.clj).
The basic generators are:

- Scalars: `just`, `sampled-from`, `for-type`, `boolean`, `integer`, `long`, `float`,
  `double`, `bytes`, `string`, `regex-str`, `symbol`, `keyword` (available in
  both simple and qualified variants)
- Special strings: `email`, `domain`, `url-str`, `ip-address-str`
- Dates and times: `local-date`, `local-time`, `local-date-time`, and their
  `-str` variants for ISO8601 strings.
- Collections: `tuple`, `hmap`, `list`, `vector`, `set`, `sorted-set`, `map`,
  `sorted-map`

There are also higher-order generators which transform or combine other
generators.

- Transform generators: `fmap`, `bind`, `filter`
- Combine generators: `one-of`, `composite`, `deferred`

Typically you'll find it most convenient to use `hegel.core/let`, which works
just like Clojure `let`, but when provided with a generator on the right hand
side, generates a random value:

```clj
(require '[hegel-clj [core :as h]
                     [generator :as g]])
(h/test! {}
  (h/let [; First, generate a random size for a collection between 2 and 64
          n       (g/integer {:min 2 :max 64})
          ; Compute a maximum value, half the size
          max-val (long (/ n 2))
          _      (h/fprn :n n :max-val max-val)
          ; Now, make a map with n elements, whose values are up in [0, max-val]
          m       (g/map {:size n}
                         (g/integer)
                         (g/integer {:min 0, :max max-val}))]
    (h/fprn m)
    ; Assert that the values in the map are distinct
    (assert (= (vals m) (distinct (vals m))))))
```

By the pigeonhole principle, a map of `n` elements to `n/2` elements must not
be injective for `2 < n`: there are some duplicate values in those maps. Hegel
finds a small map with this property: `{0 0, 1 0}`.

```clj
...
:n 2 :max-val 1
{0 0, 1 0}
{:passed? false,
 :status :failed,
 :statistics {:total 3022,
              :valid 221,
              :invalid 1,
              :overrun 2537,
              :interesting 263},
 :health-check-failed? false,
 :failures
 ({:draws {"n" 2, "m" {0 0, 1 0}},
   :exception
   #error {
     :cause "Assert failed: (= (vals m) (distinct (vals m)))"
     :via
     [{:type java.lang.AssertionError
       :message "Assert failed: (= (vals m) (distinct (vals m)))"
       :at [my_test$eval3058$case__3059 invoke "form-init17666097679615558382.clj" 13]}]
     :trace
     [[my_test$eval3058$case__3059 invoke "form-init17666097679615558382.clj" 13]
      [clojure.lang.AFn applyToHelper "AFn.java" 154]
      ...]}
  :reproduce-blob "AXicY2UAAi5GIMEEoRhQKEYoDwAFcwBA"
  :caveat nil})}
```

### Transforming Generators

There are also two higher-order generators, `fmap` and `bind`, which are
helpful for producing composable generators you can pass around. The `fmap`
function wraps a generator in one whose values are transformed by some function.
Here we transform the `(g/integer)` generator by converting its values to
`BigInt`s:

```clj
(h/test! {:test-cases 5}
  (h/let [x (g/fmap bigint (g/integer))]
    (prn x)))
0N
13242N
2147483647N
-1382N
-167793N
```

The `bind` generator works like `fmap`, but its function takes a value and
returns a *new* generator, which is asked to produce a value in turn. This
generator produces square matrices between 5 and 10 elements on a side.

```clj
(require '[clojure.pprint :refer [pprint]])
(let [matrix (->> ; Pick a rank between 5 and 10
                  (g/integer {:min 5, :max 10})
                  ; Take that rank and produce [rank elements] pairs,
                  ; with rank^2 elements
                  (g/bind (fn [rank]
                            (g/tuple (g/just rank)
                                     (g/vector {:size (* rank rank)}
                                               (g/integer {:min 0 :max 9})))))
                  ; Take those [rank elements] pairs and rearrange the flat
                  ; elements into nested vectors
                  (g/fmap (fn [[rank flat-elements]]
                            (->> flat-elements
                                 (partition rank)
                                 (mapv vec)))))]
  (h/test! {:test-cases 10}
    (h/let [m matrix]
      (pprint m))))

...

[[2 9 9 8 0 7 9 0 9 3]
 [5 3 8 9 5 3 5 7 1 7]
 [5 5 0 0 7 5 8 0 8 4]
 [7 7 1 4 7 2 4 5 4 1]
 [8 7 9 1 4 8 0 6 0 6]
 [3 5 8 7 7 0 8 3 6 9]
 [1 9 6 7 3 6 8 1 7 9]
 [8 9 4 0 7 4 8 3 2 9]
 [8 4 7 1 0 4 9 2 1 9]
 [0 6 9 1 5 3 3 0 3 3]]
[[1 5 9 3 4 6 9]
 [5 0 5 2 3 2 8]
 [7 2 0 8 1 9 3]
 [9 0 7 3 4 3 6]
 [1 4 6 4 4 7 8]
 [9 0 8 5 8 2 3]
 [1 1 4 4 4 5 1]]
```

The generators API is built with `->>` composition in mind: if a generator
takes a generator, that usually comes as the final argument. Of course you
could write this example more plainly with `h/let`, but this definition of
`matrix` is a composable object you can re-use in other generators.

Sometimes a generator would be awkward to write with `bind` or `fmap`---for
example, if you want to draw several random values which depend on each other.
For this we have `composite`: a generator which evaluates arbitrary code.

```clj
(h/sample 2
  (g/composite
    (h/let [name      (g/string)
            cat-count (g/integer 0 2)
            dog-count (+ cat-count (h/draw! (g/integer 0 2)))
            cats      (g/vector {:size cat-count, :unique? true} (g/string))
            dogs      (g/vector {:size dog-count, :unique? true} (g/string))]
      {:name name
       :pet-count (+ cat-count dog-count)
       :pets {:cats cats, :dogs dogs}})))

[{:name "", :pet-count 0, :pets {:cats [], :dogs []}}
 {:name "", :pet-count 4, :pets {:cats ["" "0..0"], :dogs ["í𔖪q𔖪" "TRUE"]}}]
```

For recursive generators, Hegel uses a stateful approach. Create a placeholder
generator with `(g/deferred)`, use it to construct another generator, then fill
in the value of `deferred` later:

```clj
(let [leaf (g/integer 0 9)
      tree (g/deferred)]
  (g/set-deferred! tree (g/one-of [leaf (g/vector tree)]))
  (mapv prn (h/sample 5 tree)))

0
[[1] 8 [2 []] 5 [] [[4 6 []]]]
[[1] 2]
3
0
```

## We Have Generative Tests At Home

The general idea of Hegel is "writing good generators and shrinkers is really
hard; we should let a few people do that work well and re-use it in different
languages". This has advantages (the Hegel authors have done a *ton* of work we
can leverage) and disadvantages (it's a separate daemon with its own runtime,
serialization costs, IPC lag, etc.)

Clojure has an excellent generative testing library called
[test.check](https://github.com/clojure/test.check). Because test.check is
functional, it relies heavily on composing and transforming generators through
`gen/bind` and friends. However, `gen/bind` is somewhat opaque to shrinking
(see [TCHECK-112](https://clojure.atlassian.net/browse/TCHECK-112), which can
lead to awkward valleys in the search terrain. For example, let's say we had a
program which [was valid unless it was passed a vector containing
42](https://github.com/clojure/test.check/blob/master/doc/growth-and-shrinking.md#gotchas-1):

```clj
(require '[clojure.test.check :as tc]
         '[clojure.test.check.generators :as tcg]
         '[clojure.test.check.properties :as tcp])
(-> (tc/quick-check 1000
      (tcp/for-all [xs (tcg/let [size tcg/nat]
                         (tcg/vector tcg/large-integer size))]
                   (not-any? #{42} xs))
      {:seed 1777986545686})
  :shrunk :smallest first)

[0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 42 0]
```

Test.check's can shrink the vector's elements towards zero, but can't shrink
the size of the vector, because it's fixed by `tcg/let`. It can also shrink the
size, but doing so puts the generator in a new RNG state where it generates
different elements--likely without `42`.

Hegel is able to shrink this example to a much more reasonable `[42]`:

```clj
(-> (h/test! {:test-cases 1000
              :seed       1777986545686}
      (h/let [size (g/integer {:min 0, :max 128})
              xs   (g/vector {:size size} (g/integer))]
        (assert (not-any? #{42} xs))))
    :failures first :draws (get "xs"))
[42]
```

## License

Copyright © 2026 Kyle Kingsbury

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
https://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.

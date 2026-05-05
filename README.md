# Hegel-clj

Clojure bindings for the [Hegel property-based testing
system](https://hegel.dev/). Hegel-clj supports the full set of Hegel schemas,
along with generator composition via `let`, `fmap`, and `bind`. It has
shrinking, final test case reporting, and test.check integration.

This is usable, but early work--I haven't implemented many of Hegel's features,
and there are a bunch of obvious user affordances missing (we have no recursive
tree generator, for instance). I'm hoping to prove out whether this is actually
*good* before going too far. Users and contributors welcome.

Hegel-core is very new, the documentation is vague, and the daemon frequently
crashes or gets stuck. It is not hard to write an incorrect or expensive
generator that breaks the daemon; I intend to but have not yet written logic to
automatically kill and restart it.

## Installation

Hegel-clj uses Hegel-core, which is a Python program. You'll need the
[UV](https://docs.astral.sh/uv/) package manager, which we use to install and
run Hegel-core. Then add hegel-clj to your project dev dependencies:

```clj
[[hegel-clj "0.1.0"]]
```

## Quickstart

Here's how to write a simple generative test for a buggy function that finds
the index of a value in a vector:

```clj
(ns my-test
  (:require [clojure.test :refer :all]
            [hegel-clj [core :refer :all]
                       [clojure-test :refer [with]]
                       [generator :as g]]))

(defn fast-index-of
  "Finds the index of element `x` in collection `xs`, or -1."
  [xs x]
  (loop [i 0]
    (if (= i (count xs))
      -1
      (if (identical? x (nth xs i))
        i
        (recur (inc i))))))

(deftest fast-index-of-test
  (with {:test-cases 100}
        [x  (g/integer)
         xs (g/vector (g/integer))]
    (prn :x x, :xs xs)
    (is (= (.indexOf xs x) (fast-index-of xs x)))))
```

Hegel runs the body of the `with` expression roughly a hundred times with
randomly generated integers for `x` and `xs`. If the `(is ...)` assertion
fails, it searches for a simpler choice of `x` and `xs` that still cause a
bug---a process called *shrinking*.

```clj
:x 0 :xs []
:x 97 :xs []
:x 7557229229202480762 :xs []
:x -2767819082036582656 :xs []
:x -100 :xs []
:x -100 :xs [-28430]
:x -1216555004209145122 :xs []
:x -22490 :xs []
:x 13808 :xs []
:x -19669 :xs []
:x 7008 :xs []
:x 27735 :xs [-18 -6395 -31652 65 -121645100408831999]
:x 27735 :xs [-18 -6395 27735 65 -121645100408831999]
:x 27735 :xs [-18 -6395 65 65 -121645100408831999]
...
:x 126 :xs [128]
:x 127 :xs [128]
:x -1 :xs [128]
:x -64 :xs [128]
:x -126 :xs [128]
:x -127 :xs [128]
:x 127 :xs [129]
:x 128 :xs [128]

FAIL in (fast-index-of-test) (form-init18001555065152736569.clj:6)
expected: 0
  actual: (-1)
```

Hegel-clj found a case where the index of an element should have been `0`, but
our `fast-index-of` function returned `-1`. You can see each version of `x` and
`y` Hegel tried, and how once it discovered a bug, it tried a variety of
smaller numbers and vectors to try and reproduce the problem.

Rather than see *every* value, we can log just those from Hegel's *final
phase*, where it replays the smallest failing example it found:

```clj
(deftest fast-index-of-test
  (with {:test-cases 100}
        [x  (g/integer)
         xs (g/vector (g/integer))]
    (fprn :x x, :xs xs)
    (is (= (.indexOf xs x) (fast-index-of xs x)))))
```

We've changed `prn` to `fprn` ("final prn"), which runs only in the final
phase. Now we only have to read the output from the smallest failing test case:

```clj
:x 128 :xs [128]

FAIL in (fast-index-of-test) (form-init18001555065152736569.clj:6)
expected: 0
  actual: (-1)
```

Bingo. Our function fails when given a vector `[128]`. Let's ask some more questions about those bad inputs...

```clj
(deftest fast-index-of-test
  (with {:test-cases 100}
        [x  (g/integer)
         xs (g/vector (g/integer))]
    (when-final
      (let [x0 (xs 0)]
        (prn :x (class x) x, :x0 (class x0) x0)
        (prn 'identical? (identical? x x0))
        (prn '= (= x x0))))
    (is (= (.indexOf xs x) (fast-index-of xs x)))))
```

The `when-final` macro evaluates its body only in the final test phase, so we
can play around without producing a zillion log lines.

```clj
:x java.lang.Long 128 :x0 java.lang.Long 128
identical? false
= true
```

Aha! So these are both java.lang.Longs, and while they're *equal* (represent
the same value) they're not *identical* (at the same memory address). Longs up
to 127 *are* identical on OpenJDK, so we wouldn't have caught this bug if we
stuck to small numbers.

## Overview

- [`hegel-clj.core`](/src/hegel-clj/core.clj) provides a friendly API to Hegel-clj, including dynamic state.
- [`hegel.generator`](/src/hegel-clj/generator.clj) constructs generators of random values.
- [`hegel.clojure-test`](/src/hegel-clj/clojure_test.clj) provides `clojure.test` integration.
- [`hegel.client`](/src/hegel-clj/client.clj) is the internal client which spawns and talks to a Hegel-core daemon.

Your main entrypoint is generally either through a test integration namespace
(like `hegel-clj.clojure-test/with`), or by starting a test using
`hegel-clj.core/run-test!`. From there, you can generate random values using
`hegel-clj.generator/let` or the lower-level `hegel-clj.core/gen`.

## Philosophy

Hegel takes an imperative approach to testing; generated values are
deterministic (and Hegel will warn you when your tests aren't!), but the
generators have implicit side effects---notably, each call to generate a value
mutates the Hegel PRNG. This is actually sort of nice: it frees you to generate
values anywhere, just like you would with `(rand)`, and work with them
incrementally.

We lean into that imperative style by making it easy to do `prn`-style
debugging, but only during the final test phase. There's a more-functional core
in there too, if you want.

## Generators

All generators live in [`hegel.core`]. The basic generators are:

- Scalars: `constant`, `boolean`, `integer`, `float`, `bytes`, `string`, `regex`, `symbol`, `keyword` (available in both simple and qualified variants)
- Special strings: `email`, `domain`, `url-str`, `ip-address-str`
- Dates and times: `local-date`, `local-time`, `local-date-time`, and their `-str` variants for ISO8601 strings.
- Collections: `tuple`, `list`, `vector`, `set`, `sorted-set`, `map`,
  `sorted-map`.
- Higher-order generators: `fmap`, `bind`

Typically you'll find it most convenient to use `g/let`, which works just like
Clojure `let`, but when provided with a generator schema on the right hand
side, generates a random value:

```clj
(require '[hegel-clj [core :as h]
                     [generator :as g]])
(run-test! {}
  (g/let [; First, generate a random size for a collection between 2 and 64
          n       (g/integer {:min 2 :max 64})
          ; Compute a maximum value, half the size
          max-val (long (/ n 2))
          ; You can print for side effects if you like!
          _       (prn :n n :max-val max-val)
          ; Now, make a map with n elements, whose values are up in [0, max-val]
          m       (g/map {:size n}
                         (g/integer)
                         (g/integer {:min 0, :max max-val}))]
    (prn m)
    ; Assert that the values in the map are distinct
    {:n n
     :m m
     :status (if (= (vals m) (distinct (vals m)))
               :valid
               :interesting)}))
```

By the pigeonhole principle, a map of `n` elements to `n/2` elements must not
be injective--there are some duplicates in this map. Hegel finds a small
map with this property: `{0 0, 1 0}`.

```clj
:n 29 :max-val 14
{-24010 1, -108 3, -8141 9, 10999 4, -157746965 1, -6658347437399882413 12, -1895 3, -57 11, 26188 4, 119 5, 1814143433 4, 4294967296 7, -61746485611849544375328652203653776230N 7, 117 9, -13461 11, -1393526811 11, -6725207902868597187 4, -53 11, 31427 8, -2233 14, 34 5, -96 2, -113 2, 24195 2, 45 8, 7103 12, 8796093022209 6, 7182105681986522026 14, -1510709640 8}
...
:n 2 :max-val 1
{0 0, 1 1}
:n 2 :max-val 1
{0 0, 1 0}
{:health-check-failure? nil,
 :final [{:n 2, :m {0 0, 1 0},:status :interesting}],
 :seed "336846547442498173212578659943701187349",
 :invalid-test-cases 7,
 :test-cases 51,
 :flaky? nil,
 :passed? false,
 :valid-test-cases 2,
 :error nil,
 :interesting-test-cases 1}
```

If you prefer, you can explicitly generate a value from a schema at any time
during a test case with `hegel-clj.core/gen`.

There are also two higher-order generators, `fmap` and `bind`, which are
helpful for producing composable generators you can pass around. The `fmap`
function wraps a generator in one whose values are transformed by some function.
Here we transform the `(g/integer)` generator by converting its values to
`BigInt`s:

```clj
(run-test! {:test-cases 5}
  (g/let [x (g/fmap bigint (g/integer))]
    (prn x)
    {:status :valid}))
0N
-6894N
25626N
28320N
25N
```

The `bind` generator works like `fmap`, but its function takes a value and
returns a *new* generator, which is asked to produce a value in turn. This
generator produces square matrices between 5 and 10 elements on a side.

```clj
(require '[hegel-clj [core :as h]
                     [generator :as g]])

(let [matrix (->> ; Pick a rank between 5 and 10
                  (g/integer {:min 5, :max 10})
                  ; Take that rank and produce [rank elements] pairs,
                  ; with rank^2 elements
                  (g/bind (fn [rank]
                            (g/tuple (g/constant rank)
                                     (g/vector {:size (* rank rank)}
                                               (g/integer {:min 0 :max 9})))))
                  ; Take those [rank elements] pairs and rearrange the flat
                  ; elements into nested vectors
                  (g/fmap (fn [[rank flat-elements]]
                            (->> flat-elements
                                 (partition rank)
                                 (mapv vec)))))]
  (run-test! {:test-cases 10}
    (g/let [m matrix]
      (pprint m)
      {:status :valid})))

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

The generators API is built with `->>` composition in mind; schemas are usually
placed in the final argument. Of course you could write this more plainly with
`g/let`, but this definition of `matrix` is a composable object you can re-use
in other generators.

## We Have Generative Tests At Home

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
(require '[hegel-clj [core :as h]
                     [generator :as hg]])
(-> (h/run-test! {:test-cases 1000
                  :seed       1777986545686}
                 (hg/let [size (hg/integer {:min 0, :max 128})
                          xs   (hg/vector {:size size} (hg/integer))]
                   {:xs     xs
                    :status (if (not-any? #{42} xs)
                              :valid
                              :interesting)}))
    :final first :xs)

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

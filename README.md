# Hegel-clj

Clojure bindings for the [Hegel property-based testing
system](https://hegel.dev/). Hegel-clj supports the full set of Hegel schemas,
along with generator composition via `let`, `fmap`, and `bind`. It has
shrinking, final test case reporting, and test.check integration.

This is usable, but early work--I haven't implemented many of Hegel's features,
and there are a bunch of obvious user affordances missing (we have no recursive
tree generator, for instance). I'm hoping to prove out whether this is actually
*good* before going too far. Users and contributors welcome.

## Installation

Hegel-clj uses Hegel-core, which is a Python program. You'll need the
[UV](https://docs.astral.sh/uv/) package manager, which we use to install and
run Hegel-core. Then add hegel-clj to your project dependencies:

```clj
[[com.aphyr.hegel-clj "0.1.0"]]
```

## Quickstart

Here's how to write a simple generative test for a buggy function that finds
the index of a value in a vector:

```clj
(ns my-test
  (:require [clojure.test :refer :all]
            [com.aphyr.hegel-clj [clojure-test :refer [with]]
                                 [gen :as g]
                                 [test :refer :all]]))


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
(require '[com.aphyr.hegel-clj.test :as h]
         '[com.aphyr.hegel-clj.gen :as hg])
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

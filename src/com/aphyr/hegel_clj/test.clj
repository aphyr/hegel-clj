(ns com.aphyr.hegel-clj.test
  "The main API for hegel-clj."
  (:require [clojure.tools.logging :refer [info warn]]
            [com.aphyr.hegel-clj [client :as c]]))

(defmacro when-final
  "Evaluates body only if this is a final test case. Helpful for logging."
  [& body]
  `(when c/*final-case?*
     ~@body))

(defmacro finfo
  "Logging macro. Calls core.logging/info, but only when in a final test
  run."
  [& args]
  `(when-final (info ~@args)))

(defmacro fwarn
  "Logging macro. Calls core.logging/warn, but only when in a final test
  run."
  [& args]
  `(when-final (warn ~@args)))

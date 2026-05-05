(defproject com.aphyr.hegel-clj "0.1.0-SNAPSHOT"
  :description "Clojure bindings for the Hegel property-based testing library"
  :url "https://github.com/aphyr/hegel-clj"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [; We only use byte-streams in, like, two places--could
                 ; probably pull it out easily.
                 [org.clj-commons/byte-streams "0.3.4"]
                 [org.clojure/clojure "1.12.2" :scope "provided"]
                 [org.clojure/tools.logging "1.3.1"]
                 [mvxcvi/clj-cbor "1.1.1"]
                 ]
  :repl-options {:init-ns com.aphyr.hegel-clj.test}
  :java-source-paths ["src"]
  :profiles {:dev {:dependencies [[org.clojure/test.check "1.1.3"]
                                  [org.slf4j/slf4j-simple "2.0.17"]]}}
  :test-selectors {:default (constantly true)
                   :focus :focus})

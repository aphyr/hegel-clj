(defproject com.aphyr/hegel-clj "0.1.0-SNAPSHOT"
  :description "Clojure bindings for the Hegel property-based testing library"
  :url "https://github.com/aphyr/hegel-clj"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[dev.hegel/hegel "0.4.0"]
                 [org.clojure/clojure "1.12.5" :scope "provided"]
                 [org.clojure/tools.logging "1.3.1"]
                 [org.opentest4j/opentest4j "1.3.0"]
                 ]
  :repl-options {:init-ns hegel-clj.core}
  :java-source-paths ["src"]
  :java-opts ["--enable-native-access=ALL-UNNAMED"]
  :profiles {:dev {:dependencies [[org.clojure/test.check "1.1.3"]
                                  [org.slf4j/slf4j-simple "2.0.17"]]}}
  :test-selectors {:default (constantly true)
                   :focus :focus})

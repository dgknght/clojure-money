(defproject clojure-money "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [
                 [org.clojure/clojure "1.6.0"]
                 [com.datomic/datomic-free "0.9.5067" :exclusions [joda-time]]]
  :main ^:skip-aot clojure-money.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})

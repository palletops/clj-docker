(defproject com.palletops/clj-docker "0.1.0-SNAPSHOT"
  :description "A clojure wrapper for the Docker API"
  :url "https://github.com/palletops/clj-docker"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.apache.commons/commons-compress "1.5"]
                 [com.palletops/api-builder "0.1.6-SNAPSHOT"]
                 [cheshire "5.3.1"]
                 [clj-http "0.9.2"]]
  :profiles {:provided {:dependencies [[org.clojure/clojure "1.6.0"]]}})

(defproject org.funtastic/hyrax "0.0.1-SNAPSHOT"
  :description "A library of tools for distributed coordination via RabbitMQ and MongoDB."
  :url "http://example.com/FIXME"
  :license {:name "Apache License"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :source-paths      ["src/clojure"]
  :java-source-paths ["src/java"]

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.slf4j/slf4j-api "1.7.7"]
                 [com.novemberain/langohr "3.3.0" :exclusions [cheshire clj-http]]]

  :global-vars {*warn-on-reflection* true}

  :profiles {:dev {:dependencies [[ch.qos.logback/logback-classic "1.1.2"]
                                  [midje "1.7.0"]]}

             ; so we can build aot versions as needed, since java is the main target of this library
             :aot-build {:aot :all}})

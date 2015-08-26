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

  :pom-plugins [[com.theoryinpractise/clojure-maven-plugin "1.3.13"
                 {:configuration [:sourceDirectories [:sourceDirectory "src/clojure"]]
                  :extensions "true"
                  :executions ([:execution [:id "no-aot-compile"]
                                [:goals ([:goal "compile"])]
                                [:phase "compile"]
                                [:configuration 
                                 [:outputDirectory "${basedir}/target/no-aot"]
                                 [:temporaryOutputDirectory true]]]

                               [:execution [:id "aot-compile"]
                                [:goals ([:goal "compile"])]
                                [:phase "compile"]
                                [:configuration 
                                 [:outputDirectory "${basedir}/target/aot"]
                                 [:temporaryOutputDirectory false]]])}]

                [org.apache.maven.plugins/maven-resources-plugin "2.7"
                 {:executions ([:execution [:id "copy-resources-no-aot"]
                                [:goals ([:goal "copy-resources"])]
                                [:phase "package"]
                                [:configuration 
                                 [:outputDirectory "${basedir}/target/no-aot"]
                                 [:resources [:resource [:directory "${basedir}/target/classes"]]]]]

                               [:execution [:id "copy-resources-aot"]
                                [:goals ([:goal "copy-resources"])]
                                [:phase "package"]
                                [:configuration [:outputDirectory "${basedir}/target/aot"]
                                 [:resources [:resource [:directory "${basedir}/target/classes"]]]]])}]

                [org.apache.maven.plugins/maven-jar-plugin "2.6"
                 {:executions ([:execution [:id "default-jar"]
                                [:phase "none"]]

                               [:execution [:id "no-aot"]
                                [:goals ([:goal "jar"])]
                                [:phase "package"]
                                [:configuration 
                                 [:classesDirectory "${basedir}/target/no-aot"]]]

                               [:execution [:id "aot"]
                                [:goals ([:goal "jar"])]
                                [:phase "package"]
                                [:configuration 
                                 [:classesDirectory "${basedir}/target/aot"]
                                 [:classifier "aot"]]])}] 

                [org.apache.maven.plugins/maven-source-plugin "2.4"
                 {:executions ([:execution [:id "sources"]
                               [:goals ([:goal "jar"])]
                               [:phase "package"]])}]]

  :profiles {:dev {:dependencies [[ch.qos.logback/logback-classic "1.1.2"]
                                  [midje "1.7.0"]]}

             ; so we can build aot versions as needed, since java is the main target of this library
             :aot-build {:aot :all}})

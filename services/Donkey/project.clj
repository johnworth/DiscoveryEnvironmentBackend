(defproject org.iplantc/donkey "3.1.3-SNAPSHOT"
  :description "Framework for hosting DiscoveryEnvironment metadata services."
  :url "https://github.com/iPlantCollaborativeOpenSource/Donkey"
  :license {:name "BSD Standard License"
            :url "http://www.iplantcollaborative.org/sites/default/files/iPLANT-LICENSE.txt"}
  :scm {:connection "scm:git:git@github.com:iPlantCollaborativeOpenSource/Donkey.git"
        :developerConnection "scm:git:git@github.com:iPlantCollaborativeOpenSource/Donkey.git"
        :url "git@github.com:iPlantCollaborativeOpenSource/Donkey.git"}
  :pom-addition [:developers
                 [:developer
                  [:url "https://github.com/orgs/iPlantCollaborativeOpenSource/teams/iplant-devs"]]]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.memoize "0.5.6"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.clojure/data.codec "0.1.0"]
                 [org.clojure/java.classpath "0.2.2"]
                 [org.apache.tika/tika-core "1.4"]
                 [org.iplantc/authy "3.1.3"]
                 [org.iplantc/clj-cas "3.1.3"]
                 [org.iplantc/clj-jargon "3.1.3"
                  :exclusions [[xerces/xmlParserAPIs]
                               [org.irods.jargon.transfer/jargon-transfer-dao-spring]]]
                 [org.iplantc/clojure-commons "3.1.3"]
                 [org.iplantc/mescal "3.1.3"]
                 [org.iplantc/kameleon "3.1.3"]
                 [org.iplantc/heuristomancer "3.1.3"]
                 [org.iplantc/clj-icat-direct "3.1.3"]
                 [org.iplantc/common-cli "3.1.3"]
                 [org/forester "1.005" ]
                 [org.nexml.model/nexml "1.5-SNAPSHOT"]
                 [net.sf.json-lib/json-lib "2.4" :classifier "jdk15"]
                 [cheshire "5.3.1"]
                 [clj-http "0.9.1"]
                 [clj-time "0.7.0"]
                 [com.cemerick/url "0.1.1"]
                 [ring "1.2.2"]
                 [compojure "1.1.7"]
                 [clojurewerkz/elastisch "1.4.0"]
                 [com.novemberain/validateur "2.1.0"]
                 [com.novemberain/welle "2.0.0"]
                 [byte-streams "0.1.10"]
                 [xerces/xercesImpl "2.11.0"]
                 [commons-net "3.3"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [net.sf.opencsv/opencsv "2.3"]
                 [com.novemberain/langohr "2.9.0"]
                 [de.ubercode.clostache/clostache "1.4.0"]
                 [me.raynes/fs "1.4.5"]
                 [dire "0.5.2"]]
  :plugins [[org.iplantc/lein-iplant-rpm "3.1.3"]
            [lein-ring "0.8.8"]
            [swank-clojure "1.4.2"]]
  :profiles {:dev {:resource-paths ["conf/test"]}}
  :aot [donkey.core]
  :main donkey.core
  :ring {:handler donkey.core/app
         :init donkey.core/lein-ring-init
         :port 31325
         :auto-reload? false}
  :iplant-rpm {:summary "iPlant Discovery Environment Business Layer Services"
               :provides "donkey"
               :dependencies ["iplant-service-config >= 0.1.0-5" "iplant-clavin" "java-1.7.0-openjdk"]
               :exe-files ["resources/scripts/filetypes/guess-2.pl"]
               :config-files ["log4j.properties"]
               :config-path "conf/main"}
  :uberjar-exclusions [#".*[.]SF" #"LICENSE" #"NOTICE"]
  :repositories [["sonatype-nexus-snapshots"
                  {:url "https://oss.sonatype.org/content/repositories/snapshots"}]
                 ["biojava"
                  {:url "http://www.biojava.org/download/maven"}]
                 ["nexml"
                  {:url "http://nexml-dev.nescent.org/.m2/repository"
                   :checksum :ignore
                   :update :daily}]])

#!/usr/bin/env bash
set -ueo pipefail
scriptDir=$(cd $(dirname $0); pwd)

libs=$scriptDir/ducttape.jar
libs=$libs:$scriptDir/lib/scala/scala-library-2.9.2.jar # DEV-ONLY

libs=$libs:$scriptDir/lib/sqlitejdbc-v056.jar # DEV-ONLY
libs=$libs:$scriptDir/lib/scala-optparse-1.1.jar # DEV-ONLY
libs=$libs:$scriptDir/lib/commons-lang3-3.1.jar # DEV-ONLY
libs=$libs:$scriptDir/lib/commons-io-2.2.jar # DEV-ONLY
libs=$libs:$scriptDir/lib/grizzled-slf4j_2.9.1-1-0.6.8.jar # DEV-ONLY

# Use slf4j's java.util logger (at runtime only) # DEV-ONLY
libs=$libs:$scriptDir/lib/slf4j-api-1.6.4.jar # DEV-ONLY
libs=$libs:$scriptDir/lib/slf4j-jdk14-1.6.4.jar # DEV-ONLY

# Specific to webui
libs=$libs:$scriptDir/lib/servlet-api-3.0.jar # DEV-ONLY
libs=$libs:$scriptDir/lib/jetty-all-8.0.4.v20111024.jar # DEV-ONLY

java -Xmx512m \
     -cp $libs \
     ducttape.webui.WebServer "$@"

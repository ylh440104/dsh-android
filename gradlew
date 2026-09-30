#!/bin/sh

#
# Gradle start up script
#

DIRNAME=$(dirname "$0")
APP_HOME=$(cd "$DIRNAME" > /dev/null && pwd)

exec java -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
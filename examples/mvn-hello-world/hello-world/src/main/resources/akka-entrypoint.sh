#!/usr/bin/env bash

# Base configuration
app_home="/app"
lib_dir="/opt/cloudflow"
java_main="cloudflow.runner.Runner"

# Java agent(s)
agents="-javaagent:/prometheus/jmx_prometheus_javaagent.jar=2050:/etc/metrics/conf/prometheus.yaml"

# Java options
java_opts="$agents $LOGBACK_CONFIG $JAVA_OPTS"

# Classpath Opts
app_config="/etc/cloudflow-runner"
java_classpath="$app_config:$lib_dir/*"

echo "Cloudflow Runner"
echo "Java opts: $java_opts"
echo "Classpath: $java_classpath"

exec java $java_opts -cp $java_classpath $java_main

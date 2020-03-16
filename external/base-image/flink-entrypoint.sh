#!/usr/bin/env bash
#
# Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

drop_privs_cmd() {
    if [ $(id -u) != 0 ]; then
        # Don't need to drop privs if EUID != 0
        return
    elif [ -x /sbin/su-exec ]; then
        # Alpine
        echo su-exec
    else
        # Others
        echo gosu flink
    fi
}

# Add in extra configs set by the operator
if [ -n "$FLINK_PROPERTIES" ]; then
    echo "$FLINK_PROPERTIES" >> "/usr/local/flink-conf.yaml"
fi

envsubst < /usr/local/flink-conf.yaml > $FLINK_HOME/conf/flink-conf.yaml
echo "web.upload.dir: $FLINK_HOME" >> "$FLINK_HOME/conf/flink-conf.yaml"
echo "jobmanager.web.upload.dir: $FLINK_HOME" >> "$FLINK_HOME/conf/flink-conf.yaml"

echo "taskmanager.memory.flink.size: 1024mb" >> "$FLINK_HOME/conf/flink-conf.yaml"

# Add JMX metric reporter to config
echo "metrics.reporters: jmx" >> "$FLINK_HOME/conf/flink-conf.yaml"

# Flink 1.9 JMX config
# https://ci.apache.org/projects/flink/flink-docs-release-1.9/monitoring/metrics.html#jmx-orgapacheflinkmetricsjmxjmxreporter
echo "metrics.reporter.jmx.factory.class: org.apache.flink.metrics.jmx.JMXReporterFactory" >> "$FLINK_HOME/conf/flink-conf.yaml"
# Flink 1.8 JMX config
# https://ci.apache.org/projects/flink/flink-docs-release-1.8/monitoring/metrics.html#jmx-orgapacheflinkmetricsjmxjmxreporter
echo "metrics.reporter.jmx.class: org.apache.flink.metrics.jmx.JMXReporter" >> "$FLINK_HOME/conf/flink-conf.yaml"

COMMAND=$@

if [ $# -lt 1 ]; then
    COMMAND="local"
fi

if [ "$COMMAND" = "help" ]; then
    echo "Usage: $(basename "$0") (jobmanager|taskmanager|local|help)"
    exit 0
elif [ "$FLINK_DEPLOYMENT_TYPE" = "jobmanager" ]; then
    echo "Starting Job Manager"
    echo "config file: " && grep '^[^\n#]' "$FLINK_HOME/conf/flink-conf.yaml"
    exec $(drop_privs_cmd) "$FLINK_HOME/bin/jobmanager.sh" start-foreground
elif [ "$FLINK_DEPLOYMENT_TYPE" = "taskmanager" ]; then
    echo "Starting Task Manager"
    echo "config file: " && grep '^[^\n#]' "$FLINK_HOME/conf/flink-conf.yaml"
    exec $(drop_privs_cmd) "$FLINK_HOME/bin/taskmanager.sh" start-foreground
elif [ "$COMMAND" = "local" ]; then
    echo "Starting local cluster"
    exec $(drop_privs_cmd) "$FLINK_HOME/bin/jobmanager.sh" start-foreground local
fi

exec "$@"

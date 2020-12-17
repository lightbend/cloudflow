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

echo "spark-entrypoint.sh..."

# Fail on errors
set -e

# Check whether there is a passwd entry for the container UID
myuid=$(id -u)
mygid=$(id -g)
# turn off -e for getent because it will return error code in anonymous uid case
set +e
uidentry=$(getent passwd $myuid)
set -e

echo "myuid: $myuid"
echo "mygid: $mygid"
echo "uidentry: $uidentry"

 if [ -n "$HADOOP_CONFIG_URL" ]; then
   ehc=/etc/hadoop/conf
   echo "Setting up hadoop config files core-site.xml and hdfs-site.xml in directory $ehc...."
   mkdir -p $ehc
   wget $HADOOP_CONFIG_URL/core-site.xml -P $ehc
   wget $HADOOP_CONFIG_URL/hdfs-site.xml -P $ehc
   export HADOOP_CONF_DIR=$ehc
fi

# If there is no passwd entry for the container UID, attempt to create one
if [ -z "$uidentry" ] ; then
    if [ -w /etc/passwd ] ; then
        echo "Attempting to create a password entry for the container UID..."
        echo "$myuid:x:$myuid:$mygid:anonymous uid:$SPARK_HOME:/bin/false" >> /etc/passwd
    else
        echo "Container ENTRYPOINT failed to add passwd entry for anonymous UID, because /etc/passwd isn't writable!"
    fi
fi

echo "JAVA_OPTS = $JAVA_OPTS"

# Add jars in /opt/cloudflow to the Spark classpath
while read -d '' -r jarfile ; do
    if [[ "$CLOUDFLOW_CLASSPATH" == "" ]]; then
        CLOUDFLOW_CLASSPATH="$jarfile";
    else
        CLOUDFLOW_CLASSPATH="$CLOUDFLOW_CLASSPATH":"$jarfile"
    fi
done < <(find /opt/cloudflow ! -type d -name '*.jar' -print0 | sort -z)
echo "CLOUDFLOW_CLASSPATH = $CLOUDFLOW_CLASSPATH"

SPARK_K8S_CMD="$1"
case "$SPARK_K8S_CMD" in
    driver | driver-py | driver-r | executor)
      shift 1
      ;;
    "")
      ;;
    *)
      echo "Non-spark-on-k8s command provided, proceeding in pass-through mode..."
      exec /sbin/tini -s -- "$@"
      ;;
esac

echo "Using SPARK_HOME: ${SPARK_HOME}"

# The following tells the JVM to add all archives in the .../jars directory.
# See https://docs.oracle.com/javase/7/docs/technotes/tools/windows/classpath.html
SPARK_CLASSPATH="$SPARK_CLASSPATH:${SPARK_HOME}/jars/*"

env | grep SPARK_JAVA_OPT_ | sort -t_ -k4 -n | sed 's/[^=]*=\(.*\)/\1/g' > /tmp/java_opts.txt
readarray -t SPARK_EXECUTOR_JAVA_OPTS < /tmp/java_opts.txt

if [ -n "$SPARK_EXTRA_CLASSPATH" ]; then
  SPARK_CLASSPATH="$SPARK_CLASSPATH:$SPARK_EXTRA_CLASSPATH"
fi
echo "SPARK_CLASSPATH = $SPARK_CLASSPATH"

if [ -n "$PYSPARK_FILES" ]; then
    PYTHONPATH="$PYTHONPATH:$PYSPARK_FILES"
fi
echo "PYTHONPATH = $PYTHONPATH"

PYSPARK_ARGS=""
if [ -n "$PYSPARK_APP_ARGS" ]; then
    PYSPARK_ARGS="$PYSPARK_APP_ARGS"
fi
echo "PYSPARK_ARGS = $PYSPARK_ARGS"

R_ARGS=""
if [ -n "$R_APP_ARGS" ]; then
    R_ARGS="$R_APP_ARGS"
fi
echo "R_ARGS = $R_ARGS"

if [ "$PYSPARK_MAJOR_PYTHON_VERSION" == "2" ]; then
    pyv="$(python -V 2>&1)"
    export PYTHON_VERSION="${pyv:7}"
    export PYSPARK_PYTHON="python"
    export PYSPARK_DRIVER_PYTHON="python"
elif [ "$PYSPARK_MAJOR_PYTHON_VERSION" == "3" ]; then
    pyv3="$(python3 -V 2>&1)"
    export PYTHON_VERSION="${pyv3:7}"
    export PYSPARK_PYTHON="python3"
    export PYSPARK_DRIVER_PYTHON="python3"
fi
echo "PYTHON_VERSION = $PYTHON_VERSION"
echo "PYSPARK_PYTHON = $PYSPARK_PYTHON"
echo "PYSPARK_DRIVER_PYTHON = $PYSPARK_DRIVER_PYTHON"

case "$SPARK_K8S_CMD" in
  driver)
    CMD=(
      "$SPARK_HOME/bin/spark-submit"
      --conf "spark.driver.bindAddress=$SPARK_DRIVER_BIND_ADDRESS"
      --conf "spark.driver.extraClassPath=$CLOUDFLOW_CLASSPATH"
      --conf "spark.kubernetes.executor.deleteOnTermination=false"
      --deploy-mode client
      "$@"
    )
    ;;
  driver-py)
    CMD=(
      "$SPARK_HOME/bin/spark-submit"
      --conf "spark.driver.bindAddress=$SPARK_DRIVER_BIND_ADDRESS"
      --conf "spark.driver.extraClassPath=$CLOUDFLOW_CLASSPATH"
      --deploy-mode client
      "$@" $PYSPARK_PRIMARY $PYSPARK_ARGS
    )
    ;;
    driver-r)
    CMD=(
      "$SPARK_HOME/bin/spark-submit"
      --conf "spark.driver.bindAddress=$SPARK_DRIVER_BIND_ADDRESS"
      --conf "spark.driver.extraClassPath=$CLOUDFLOW_CLASSPATH"
      --deploy-mode client
      "$@" $R_PRIMARY $R_ARGS
    )
    ;;
  executor)
    CMD=(
      ${JAVA_HOME}/bin/java
      "${SPARK_EXECUTOR_JAVA_OPTS[@]}"
      -Xms$SPARK_EXECUTOR_MEMORY
      -Xmx$SPARK_EXECUTOR_MEMORY
      -cp "$SPARK_CLASSPATH:$CLOUDFLOW_CLASSPATH"
      org.apache.spark.executor.CoarseGrainedExecutorBackend
      --driver-url $SPARK_DRIVER_URL
      --executor-id $SPARK_EXECUTOR_ID
      --cores $SPARK_EXECUTOR_CORES
      --app-id $SPARK_APPLICATION_ID
      --hostname $SPARK_EXECUTOR_POD_IP
    )
    ;;

  *)
    echo "Unknown command: $SPARK_K8S_CMD" 1>&2
    exit 1
esac

echo "SPARK_K8S_CMD = ${SPARK_K8S_CMD[@]}"

# Execute the container CMD under tini for better hygiene
echo "running: exec /sbin/tini -s -- ${CMD[@]}"
exec /sbin/tini -s -- "${CMD[@]}"

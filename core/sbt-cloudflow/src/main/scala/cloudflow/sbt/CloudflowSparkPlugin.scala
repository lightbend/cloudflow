/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.sbt

import sbt._
import sbt.Keys._
import sbtdocker._
import sbtdocker.DockerKeys._
import sbtdocker.Instructions
import sbtdocker.staging.CopyFile
import com.typesafe.sbt.packager.Keys._

import cloudflow.sbt.CloudflowKeys._
import CloudflowBasePlugin._

object CloudflowSparkPlugin extends AutoPlugin {

  override def requires = CloudflowBasePlugin

  override def projectSettings = Seq(
    cloudflowSparkBaseImage := None,
    libraryDependencies ++= Vector(
          "com.lightbend.cloudflow" % s"cloudflow-spark_${(ThisProject / scalaBinaryVersion).value}"         % (ThisProject / cloudflowVersion).value,
          "com.lightbend.cloudflow" % s"cloudflow-spark-testkit_${(ThisProject / scalaBinaryVersion).value}" % (ThisProject / cloudflowVersion).value % "test"
        ),
    cloudflowDockerImageName := Def.task {
          Some(DockerImageName((ThisProject / name).value.toLowerCase, (ThisProject / version).value))
        }.value,
    buildOptions in docker := BuildOptions(
          cache = true,
          removeIntermediateContainers = BuildOptions.Remove.OnSuccess,
          pullBaseImage = BuildOptions.Pull.IfMissing
        ),
    cloudflowStageAppJars := Def.taskDyn {
          Def.task {
            val stagingDir  = stage.value
            val projectJars = (Runtime / internalDependencyAsJars).value.map(_.data)
            val depJars     = (Runtime / externalDependencyClasspath).value.map(_.data)

            val appJarDir = new File(stagingDir, AppJarsDir)
            val depJarDir = new File(stagingDir, DepJarsDir)
            projectJars.foreach { jar =>
              IO.copyFile(jar, new File(appJarDir, jar.getName))
            }
            depJars.foreach { jar =>
              IO.copyFile(jar, new File(depJarDir, jar.getName))
            }
          }
        }.value,
    baseDockerInstructions := {
      val appDir: File     = stage.value
      val appJarsDir: File = new File(appDir, AppJarsDir)
      val depJarsDir: File = new File(appDir, DepJarsDir)

      val metricsProperties = (ThisProject / target).value / "cloudflow" / "spark" / "metrics.properties"
      IO.write(metricsProperties, metricsPropertiesContent)

      val prometheusYaml = (ThisProject / target).value / "cloudflow" / "spark" / "prometheus.yaml"
      IO.write(prometheusYaml, prometheusYamlContent)

      val log4jProperties = (ThisProject / target).value / "cloudflow" / "spark" / "log4j.properties"
      IO.write(log4jProperties, log4jPropertiesContent)

      val sparkEntrypointSh = (ThisProject / target).value / "cloudflow" / "spark" / "spark-entrypoint.sh"
      IO.write(sparkEntrypointSh, sparkEntrypointShContent)

      val scalaVersion = (ThisProject / scalaBinaryVersion).value
      val sparkVersion = "2.4.5"
      val sparkHome    = "/opt/spark"

      val sparkTgz    = s"spark-${sparkVersion}-bin-cloudflow-${scalaVersion}.tgz"
      val sparkTgzUrl = s"https://github.com/lightbend/spark/releases/download/${sparkVersion}-lightbend/${sparkTgz}"

      val tiniVersion = "v0.18.0"

      Seq(
        Instructions.Run.exec(Seq("wget", sparkTgzUrl)),
        Instructions.Run.exec(Seq("tar", "-xvzf", sparkTgz)),
        Instructions.Run.exec(Seq("mkdir", "-p", sparkHome)),
        Instructions.Run.exec(Seq("cp", "-r", s"spark-${sparkVersion}-bin-cloudflow-${scalaVersion}/jars", s"${sparkHome}/jars")),
        Instructions.Run.exec(Seq("cp", "-r", s"spark-${sparkVersion}-bin-cloudflow-${scalaVersion}/bin", s"${sparkHome}/bin")),
        Instructions.Run.exec(Seq("cp", "-r", s"spark-${sparkVersion}-bin-cloudflow-${scalaVersion}/sbin", s"${sparkHome}/sbin")),
        Instructions.Run.exec(Seq("cp", "-r", s"spark-${sparkVersion}-bin-cloudflow-${scalaVersion}/examples", s"${sparkHome}/examples")),
        Instructions.Run.exec(Seq("cp", "-r", s"spark-${sparkVersion}-bin-cloudflow-${scalaVersion}/data", s"${sparkHome}/data")),
        Instructions.Run
          .exec(Seq("cp", s"spark-${sparkVersion}-bin-cloudflow-${scalaVersion}/kubernetes/dockerfiles/spark/entrypoint.sh", "/opt/")),
        Instructions.Run.exec(Seq("rm", sparkTgz)),
        Instructions.Run.exec(Seq("rm", "-rf", s"spark-${sparkVersion}-bin-cloudflow-${scalaVersion}")),
        Instructions.Copy(CopyFile(metricsProperties), "/etc/metrics/conf/metrics.properties"),
        Instructions.Copy(CopyFile(prometheusYaml), "/etc/metrics/conf/prometheus.yaml"),
        Instructions.Copy(CopyFile(log4jProperties), s"${sparkHome}/conf/log4j.properties"),
        Instructions.Copy(CopyFile(sparkEntrypointSh), "/opt/spark-entrypoint.sh"),
        Instructions.Run.exec(Seq("chmod", "a+x", "/opt/spark-entrypoint.sh")),
        Instructions.Run.exec(Seq("ln", "-s", "/lib", "/lib64")),
        Instructions.Run.exec(Seq("apk", "add", "bash", "curl")),
        Instructions.Run.exec(Seq("mkdir", "-p", "/opt/spark/work-dir")),
        Instructions.Run.exec(Seq("touch", "/opt/spark/RELEASE")),
        Instructions.Run.exec(Seq("chgrp", "root", "/etc/passwd")),
        Instructions.Run.exec(Seq("chmod", "ug+rw", "/etc/passwd")),
        Instructions.Run.exec(Seq("rm", "-rf", "/var/cache/apt/*")),
        Instructions.Run
          .exec(Seq("curl", "-L", s"https://github.com/krallin/tini/releases/download/${tiniVersion}/tini", "-o", "/sbin/tini")),
        Instructions.Run.exec(Seq("chmod", "+x", "/sbin/tini")),
        Instructions.Run.exec(Seq("addgroup", "-S", "-g", "185", "cloudflow")),
        Instructions.Run.exec(Seq("adduser", "-u", "185", "-S", "-h", "/home/cloudflow", "-s", "/sbin/nologin", "cloudflow", "root")),
        Instructions.Run.exec(Seq("adduser", "cloudflow", "cloudflow")),
        Instructions.Run.exec(Seq("mkdir", "-p", "/prometheus")),
        Instructions.Run.exec(
          Seq(
            "curl",
            "https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.11.0/jmx_prometheus_javaagent-0.11.0.jar",
            "-o",
            "/prometheus/jmx_prometheus_javaagent.jar"
          )
        ),
        Instructions.Run.exec(Seq("chmod", "ug+rwX", "/home/cloudflow")),
        Instructions.Run.exec(Seq("mkdir", "-p", "/opt/spark/conf")),
        Instructions.Run.exec(Seq("chgrp", "-R", "0", "/opt/spark")),
        Instructions.Run.exec(Seq("chmod", "-R", "g=u", "/opt/spark")),
        Instructions.Run.exec(Seq("chmod", "-R", "u+x", "/opt/spark/bin")),
        Instructions.Run.exec(Seq("chmod", "-R", "u+x", "/opt/spark/sbin")),
        Instructions.Run.exec(Seq("chgrp", "-R", "0", "/prometheus")),
        Instructions.Run.exec(Seq("chmod", "-R", "g=u", "/prometheus")),
        Instructions.Run.exec(Seq("chgrp", "-R", "0", "/etc/metrics/conf")),
        Instructions.Run.exec(Seq("chmod", "-R", "g=u", "/etc/metrics/conf")),
        Instructions.Run.exec(Seq("chown", "cloudflow:root", "/opt")),
        Instructions.Run.exec(Seq("chmod", "775", "/opt")),
        Instructions.Env("SPARK_HOME", sparkHome),
        Instructions.Env("SPARK_VERSION", sparkVersion),
        Instructions.WorkDir("/opt/spark/work-dir"),
        Instructions.Run.exec(Seq("chmod", "g+w", "/opt/spark/work-dir")),
        Instructions.EntryPoint.exec(Seq("bash", "/opt/spark-entrypoint.sh")),
        Instructions.User(UserInImage),
        Instructions.Copy(sources = Seq(CopyFile(depJarsDir)), destination = OptAppDir, chown = Some(userAsOwner(UserInImage))),
        Instructions.Copy(sources = Seq(CopyFile(appJarsDir)), destination = OptAppDir, chown = Some(userAsOwner(UserInImage))),
        Instructions.Expose(Seq(4040))
      )
    },
    dockerfile in docker := {
      val log = streams.value.log
      // this triggers side-effects, e.g. files being created in the staging area
      cloudflowStageAppJars.value

      cloudflowSparkBaseImage.value match {
        case Some(baseImage) =>
          log.warn("'cloudflowFlinkBaseImage' is defined, 'cloudflowDockerBaseImage' setting is going to be ignored")

          val appDir: File     = stage.value
          val appJarsDir: File = new File(appDir, AppJarsDir)
          val depJarsDir: File = new File(appDir, DepJarsDir)

          new Dockerfile {
            from(baseImage)
            user(UserInImage)
            copy(depJarsDir, OptAppDir, chown = userAsOwner(UserInImage))
            copy(appJarsDir, OptAppDir, chown = userAsOwner(UserInImage))
            addInstructions(extraDockerInstructions.value)
            expose(4040)
          }
        case _ =>
          new Dockerfile {
            from(cloudflowDockerBaseImage.value)
            addInstructions((ThisProject / baseDockerInstructions).value)
            addInstructions((ThisProject / extraDockerInstructions).value)
          }
      }
    }
  )

  private val metricsPropertiesContent =
    """#
      |# Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
      |#
      |# Licensed to the Apache Software Foundation (ASF) under one or more
      |# contributor license agreements.  See the NOTICE file distributed with
      |# this work for additional information regarding copyright ownership.
      |# The ASF licenses this file to You under the Apache License, Version 2.0
      |# (the "License"); you may not use this file except in compliance with
      |# the License.  You may obtain a copy of the License at
      |#
      |#    http://www.apache.org/licenses/LICENSE-2.0
      |#
      |# Unless required by applicable law or agreed to in writing, software
      |# distributed under the License is distributed on an "AS IS" BASIS,
      |# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
      |# See the License for the specific language governing permissions and
      |# limitations under the License.
      |#
      |
      |*.sink.jmx.class=org.apache.spark.metrics.sink.JmxSink
      |driver.source.jvm.class=org.apache.spark.metrics.source.JvmSource
      |executor.source.jvm.class=org.apache.spark.metrics.source.JvmSource
      |""".stripMargin

  private val prometheusYamlContent =
    """#
      |# Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
      |#
      |# Licensed to the Apache Software Foundation (ASF) under one or more
      |# contributor license agreements.  See the NOTICE file distributed with
      |# this work for additional information regarding copyright ownership.
      |# The ASF licenses this file to You under the Apache License, Version 2.0
      |# (the "License"); you may not use this file except in compliance with
      |# the License.  You may obtain a copy of the License at
      |#
      |#    http://www.apache.org/licenses/LICENSE-2.0
      |#
      |# Unless required by applicable law or agreed to in writing, software
      |# distributed under the License is distributed on an "AS IS" BASIS,
      |# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
      |# See the License for the specific language governing permissions and
      |# limitations under the License.
      |#
      |rules:
      |
      |  # These come from the master
      |  # Example: master.aliveWorkers
      |  - pattern: "metrics<name=master\\.(.*)><>Value"
      |    name: spark_master_$1
      |
      |  # These come from the worker
      |  # Example: worker.coresFree
      |  - pattern: "metrics<name=worker\\.(.*)><>Value"
      |    name: spark_worker_$1
      |
      |  # These come from the application driver
      |  # Example: app-20160809000059-0000.driver.DAGScheduler.stage.failedStages
      |  - pattern: "metrics<name=(.*)\\.driver\\.(DAGScheduler|BlockManager|jvm|appStatus)\\.(.*)><>Value"
      |    name: spark_driver_$2_$3
      |    type: GAUGE
      |    labels:
      |      app_id: "$1"
      |
      |  # These come from the application driver
      |  # Counters for appStatus
      |  - pattern: "metrics<name=(.*)\\.driver\\.appStatus\\.(.*)><>Count"
      |    name: spark_driver_appStatus_$2_count
      |    type: COUNTER
      |    labels:
      |      app_id: "$1"
      |
      |  # Gauge for appStatus
      |  - pattern: "metrics<name=(.*)\\.driver\\.appStatus\\.(.*)><>Value"
      |    name: spark_driver_appStatus_$2
      |    type: GAUGE
      |    labels:
      |      app_id: "$1"
      |
      |  # These come from the application driver
      |  # Emulate timers for DAGScheduler like messagePRocessingTime
      |  - pattern: "metrics<name=(.*)\\.driver\\.DAGScheduler\\.(.*)><>Count"
      |    name: spark_driver_DAGScheduler_$2_count
      |    type: COUNTER
      |    labels:
      |      app_id: "$1"
      |
      |  # HiveExternalCatalog is of type counter
      |  - pattern: "metrics<name=(.*)\\.driver\\.HiveExternalCatalog\\.(.*)><>Count"
      |    name: spark_driver_HiveExternalCatalog_$2_total
      |    type: COUNTER
      |    labels:
      |      app_id: "$1"
      |
      |  # These come from the application driver
      |  # Emulate histograms for CodeGenerator
      |  - pattern: "metrics<name=(.*)\\.driver\\.CodeGenerator\\.(.*)><>Count"
      |    name: spark_driver_CodeGenerator_$2_count
      |    type: COUNTER
      |    labels:
      |      app_id: "$1"
      |
      |  # These come from the application driver
      |  # Emulate timer (keep only count attribute) plus counters for LiveListenerBus
      |  - pattern: "metrics<name=(.*)\\.driver\\.LiveListenerBus\\.(.*)><>Count"
      |    name: spark_driver_LiveListenerBus_$2_count
      |    type: COUNTER
      |    labels:
      |      app_id: "$1"
      |
      |  # Get Gauge type metrics for LiveListenerBus
      |  - pattern: "metrics<name=(.*)\\.driver\\.LiveListenerBus\\.(.*)><>Value"
      |    name: spark_driver_LiveListenerBus_$2
      |    type: GAUGE
      |    labels:
      |      app_id: "$1"
      |
      |  # These come from the application driver if it's a streaming application
      |  # Example: app-20160809000059-0000.driver.com.example.ClassName.StreamingMetrics.streaming.lastCompletedBatch_schedulingDelay
      |  - pattern: "metrics<name=(.*)\\.driver\\.(.*)\\.StreamingMetrics\\.streaming\\.(.*)><>Value"
      |    name: spark_driver_streaming_$3
      |    labels:
      |      app_id: "$1"
      |      app_name: "$2"
      |
      |  # These come from the application driver if it's a structured streaming application
      |  # Example: app-20160809000059-0000.driver.spark.streaming.QueryName.inputRate-total
      |  - pattern: "metrics<name=(.*)\\.driver\\.spark\\.streaming\\.(.*)\\.(.*)><>Value"
      |    name: spark_driver_structured_streaming_$3
      |    labels:
      |      app_id: "$1"
      |      query_name: "$2"
      |
      |  # These come from the application executors
      |  # Example: app-20160809000059-0000.0.executor.threadpool.activeTasks (value)
      |  #  app-20160809000059-0000.0.executor.JvmGCtime (counter)
      |  - pattern: "metrics<name=(.*)\\.(.*)\\.executor\\.(.*)><>Value"
      |    name: spark_executor_$3
      |    type: GAUGE
      |    labels:
      |      app_id: "$1"
      |      executor_id: "$2"
      |
      |  # Executors counters
      |  - pattern: "metrics<name=(.*)\\.(.*)\\.executor\\.(.*)><>Count"
      |    name: spark_executor_$3_total
      |    type: COUNTER
      |    labels:
      |      app_id: "$1"
      |      executor_id: "$2"
      |
      |  # These come from the application executors
      |  # Example: app-20160809000059-0000.0.jvm.threadpool.activeTasks
      |  - pattern: "metrics<name=(.*)\\.([0-9]+)\\.(jvm|NettyBlockTransfer)\\.(.*)><>Value"
      |    name: spark_executor_$3_$4
      |    type: GAUGE
      |    labels:
      |      app_id: "$1"
      |      executor_id: "$2"
      |
      |  - pattern: "metrics<name=(.*)\\.([0-9]+)\\.HiveExternalCatalog\\.(.*)><>Count"
      |    name: spark_executor_HiveExternalCatalog_$3_count
      |    type: COUNTER
      |    labels:
      |      app_id: "$1"
      |      executor_id: "$2"
      |
      |  # These come from the application driver
      |  # Emulate histograms for CodeGenerator
      |  - pattern: "metrics<name=(.*)\\.([0-9]+)\\.CodeGenerator\\.(.*)><>Count"
      |    name: spark_executor_CodeGenerator_$3_count
      |    type: COUNTER
      |    labels:
      |      app_id: "$1"
      |      executor_id: "$2"
      |
      |  # Kafka Consumer lag and throughput, Kafka Producer throughput metrics
      |
      |  - pattern: "kafka\\.consumer<type=consumer-fetch-manager-metrics,(\\s*)client-id=(\\S+),(\\s*)topic=(\\S+),(\\s*)partition=([0-9]+)(.*)><>records-lag-max"
      |    name: kafka_consumer_consumer_fetch_manager_metrics_records_lag_max
      |    type: GAUGE
      |    labels:
      |      client_id: "$2"
      |      topic: "$4"
      |      partition: "$6"
      |  - pattern: "kafka\\.consumer<type=consumer-fetch-manager-metrics,(\\s*)client-id=(\\S+),(\\s*)topic=(\\S+)(.*)><>records-consumed-rate"
      |    name: kafka_consumer_consumer_fetch_manager_metrics_records_consumed_rate
      |    type: GAUGE
      |    labels:
      |      client_id: "$2"
      |      topic: "$4"
      |  - pattern: "kafka\\.producer<type=producer-topic-metrics,(\\s*)client-id=(\\S+),(\\s*)topic=(\\S+)(.*)><>record-send-rate"
      |    name: kafka_producer_producer_metrics_record_send_rate
      |    type: GAUGE
      |    labels:
      |      client_id: "$2"
      |      topic: "$4"
      |""".stripMargin

  private val log4jPropertiesContent =
    """#
      |# Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
      |#
      |# Licensed to the Apache Software Foundation (ASF) under one or more
      |# contributor license agreements.  See the NOTICE file distributed with
      |# this work for additional information regarding copyright ownership.
      |# The ASF licenses this file to You under the Apache License, Version 2.0
      |# (the "License"); you may not use this file except in compliance with
      |# the License.  You may obtain a copy of the License at
      |#
      |#    http://www.apache.org/licenses/LICENSE-2.0
      |#
      |# Unless required by applicable law or agreed to in writing, software
      |# distributed under the License is distributed on an "AS IS" BASIS,
      |# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
      |# See the License for the specific language governing permissions and
      |# limitations under the License.
      |#
      |
      |# Set everything to be logged to the console
      |log4j.rootCategory=INFO, console
      |log4j.appender.console=org.apache.log4j.ConsoleAppender
      |log4j.appender.console.target=System.err
      |log4j.appender.console.layout=org.apache.log4j.PatternLayout
      |log4j.appender.console.layout.ConversionPattern=%d{yy/MM/dd HH:mm:ss} %p %c{1}: %m%n
      |
      |# Set the default spark-shell log level to WARN. When running the spark-shell, the
      |# log level for this class is used to overwrite the root logger's log level, so that
      |# the user can have different defaults for the shell and regular Spark apps.
      |log4j.logger.org.apache.spark.repl.Main=WARN
      |
      |# Settings to quiet third party logs that are too verbose
      |log4j.logger.org.spark_project.jetty=WARN
      |log4j.logger.org.spark_project.jetty.util.component.AbstractLifeCycle=ERROR
      |log4j.logger.org.apache.spark.repl.SparkIMain$exprTyper=INFO
      |log4j.logger.org.apache.spark.repl.SparkILoop$SparkILoopInterpreter=INFO
      |log4j.logger.org.apache.parquet=ERROR
      |log4j.logger.parquet=ERROR
      |
      |# SPARK-9183: Settings to avoid annoying messages when looking up nonexistent UDFs in SparkSQL with Hive support
      |log4j.logger.org.apache.hadoop.hive.metastore.RetryingHMSHandler=FATAL
      |log4j.logger.org.apache.hadoop.hive.ql.exec.FunctionRegistry=ERROR
      |
      |""".stripMargin

  private val sparkEntrypointShContent =
    """#!/usr/bin/env bash
      |#
      |# Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
      |#
      |# Licensed to the Apache Software Foundation (ASF) under one or more
      |# contributor license agreements.  See the NOTICE file distributed with
      |# this work for additional information regarding copyright ownership.
      |# The ASF licenses this file to You under the Apache License, Version 2.0
      |# (the "License"); you may not use this file except in compliance with
      |# the License.  You may obtain a copy of the License at
      |#
      |#    http://www.apache.org/licenses/LICENSE-2.0
      |#
      |# Unless required by applicable law or agreed to in writing, software
      |# distributed under the License is distributed on an "AS IS" BASIS,
      |# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
      |# See the License for the specific language governing permissions and
      |# limitations under the License.
      |#
      |
      |echo "spark-entrypoint.sh..."
      |
      |# Fail on errors
      |set -e
      |
      |# Check whether there is a passwd entry for the container UID
      |myuid=$(id -u)
      |mygid=$(id -g)
      |# turn off -e for getent because it will return error code in anonymous uid case
      |set +e
      |uidentry=$(getent passwd $myuid)
      |set -e
      |
      |echo "myuid: $myuid"
      |echo "mygid: $mygid"
      |echo "uidentry: $uidentry"
      |
      | if [ -n "$HADOOP_CONFIG_URL" ]; then
      |   ehc=/etc/hadoop/conf
      |   echo "Setting up hadoop config files core-site.xml and hdfs-site.xml in directory $ehc...."
      |   mkdir -p $ehc
      |   wget $HADOOP_CONFIG_URL/core-site.xml -P $ehc
      |   wget $HADOOP_CONFIG_URL/hdfs-site.xml -P $ehc
      |   export HADOOP_CONF_DIR=$ehc
      |fi
      |
      |# If there is no passwd entry for the container UID, attempt to create one
      |if [ -z "$uidentry" ] ; then
      |    if [ -w /etc/passwd ] ; then
      |        echo "Attempting to create a password entry for the container UID..."
      |        echo "$myuid:x:$myuid:$mygid:anonymous uid:$SPARK_HOME:/bin/false" >> /etc/passwd
      |    else
      |        echo "Container ENTRYPOINT failed to add passwd entry for anonymous UID, because /etc/passwd isn't writable!"
      |    fi
      |fi
      |
      |# Add jars in /opt/cloudflow to the Spark classpath
      |while read -d '' -r jarfile ; do
      |    if [[ "$CLOUDFLOW_CLASSPATH" == "" ]]; then
      |        CLOUDFLOW_CLASSPATH="$jarfile";
      |    else
      |        CLOUDFLOW_CLASSPATH="$CLOUDFLOW_CLASSPATH":"$jarfile"
      |    fi
      |done < <(find /opt/cloudflow ! -type d -name '*.jar' -print0 | sort -z)
      |echo "CLOUDFLOW_CLASSPATH = $CLOUDFLOW_CLASSPATH"
      |
      |SPARK_K8S_CMD="$1"
      |case "$SPARK_K8S_CMD" in
      |    driver | driver-py | driver-r | executor)
      |      shift 1
      |      ;;
      |    "")
      |      ;;
      |    *)
      |      echo "Non-spark-on-k8s command provided, proceeding in pass-through mode..."
      |      exec /sbin/tini -s -- "$@"
      |      ;;
      |esac
      |
      |echo "Using SPARK_HOME: ${SPARK_HOME}"
      |
      |# The following tells the JVM to add all archives in the .../jars directory.
      |# See https://docs.oracle.com/javase/7/docs/technotes/tools/windows/classpath.html
      |SPARK_CLASSPATH="$SPARK_CLASSPATH:${SPARK_HOME}/jars/*"
      |
      |env | grep SPARK_JAVA_OPT_ | sort -t_ -k4 -n | sed 's/[^=]*=\(.*\)/\1/g' > /tmp/java_opts.txt
      |readarray -t SPARK_EXECUTOR_JAVA_OPTS < /tmp/java_opts.txt
      |
      |if [ -n "$SPARK_EXTRA_CLASSPATH" ]; then
      |  SPARK_CLASSPATH="$SPARK_CLASSPATH:$SPARK_EXTRA_CLASSPATH"
      |fi
      |echo "SPARK_CLASSPATH = $SPARK_CLASSPATH"
      |
      |if [ -n "$PYSPARK_FILES" ]; then
      |    PYTHONPATH="$PYTHONPATH:$PYSPARK_FILES"
      |fi
      |echo "PYTHONPATH = $PYTHONPATH"
      |
      |PYSPARK_ARGS=""
      |if [ -n "$PYSPARK_APP_ARGS" ]; then
      |    PYSPARK_ARGS="$PYSPARK_APP_ARGS"
      |fi
      |echo "PYSPARK_ARGS = $PYSPARK_ARGS"
      |
      |R_ARGS=""
      |if [ -n "$R_APP_ARGS" ]; then
      |    R_ARGS="$R_APP_ARGS"
      |fi
      |echo "R_ARGS = $R_ARGS"
      |
      |if [ "$PYSPARK_MAJOR_PYTHON_VERSION" == "2" ]; then
      |    pyv="$(python -V 2>&1)"
      |    export PYTHON_VERSION="${pyv:7}"
      |    export PYSPARK_PYTHON="python"
      |    export PYSPARK_DRIVER_PYTHON="python"
      |elif [ "$PYSPARK_MAJOR_PYTHON_VERSION" == "3" ]; then
      |    pyv3="$(python3 -V 2>&1)"
      |    export PYTHON_VERSION="${pyv3:7}"
      |    export PYSPARK_PYTHON="python3"
      |    export PYSPARK_DRIVER_PYTHON="python3"
      |fi
      |echo "PYTHON_VERSION = $PYTHON_VERSION"
      |echo "PYSPARK_PYTHON = $PYSPARK_PYTHON"
      |echo "PYSPARK_DRIVER_PYTHON = $PYSPARK_DRIVER_PYTHON"
      |
      |case "$SPARK_K8S_CMD" in
      |  driver)
      |    CMD=(
      |      "$SPARK_HOME/bin/spark-submit"
      |      --conf "spark.driver.bindAddress=$SPARK_DRIVER_BIND_ADDRESS"
      |      --conf "spark.driver.extraClassPath=$CLOUDFLOW_CLASSPATH"
      |      --conf "spark.kubernetes.executor.deleteOnTermination=false"
      |      --deploy-mode client
      |      "$@"
      |    )
      |    ;;
      |  driver-py)
      |    CMD=(
      |      "$SPARK_HOME/bin/spark-submit"
      |      --conf "spark.driver.bindAddress=$SPARK_DRIVER_BIND_ADDRESS"
      |      --conf "spark.driver.extraClassPath=$CLOUDFLOW_CLASSPATH"
      |      --deploy-mode client
      |      "$@" $PYSPARK_PRIMARY $PYSPARK_ARGS
      |    )
      |    ;;
      |    driver-r)
      |    CMD=(
      |      "$SPARK_HOME/bin/spark-submit"
      |      --conf "spark.driver.bindAddress=$SPARK_DRIVER_BIND_ADDRESS"
      |      --conf "spark.driver.extraClassPath=$CLOUDFLOW_CLASSPATH"
      |      --deploy-mode client
      |      "$@" $R_PRIMARY $R_ARGS
      |    )
      |    ;;
      |  executor)
      |    CMD=(
      |      ${JAVA_HOME}/bin/java
      |      "${SPARK_EXECUTOR_JAVA_OPTS[@]}"
      |      -Xms$SPARK_EXECUTOR_MEMORY
      |      -Xmx$SPARK_EXECUTOR_MEMORY
      |      -cp "$SPARK_CLASSPATH:$CLOUDFLOW_CLASSPATH"
      |      org.apache.spark.executor.CoarseGrainedExecutorBackend
      |      --driver-url $SPARK_DRIVER_URL
      |      --executor-id $SPARK_EXECUTOR_ID
      |      --cores $SPARK_EXECUTOR_CORES
      |      --app-id $SPARK_APPLICATION_ID
      |      --hostname $SPARK_EXECUTOR_POD_IP
      |    )
      |    ;;
      |
      |  *)
      |    echo "Unknown command: $SPARK_K8S_CMD" 1>&2
      |    exit 1
      |esac
      |
      |echo "SPARK_K8S_CMD = ${SPARK_K8S_CMD[@]}"
      |
      |# Execute the container CMD under tini for better hygiene
      |echo "running: exec /sbin/tini -s -- ${CMD[@]}"
      |exec /sbin/tini -s -- "${CMD[@]}"
      |""".stripMargin

}

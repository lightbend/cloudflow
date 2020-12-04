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

object CloudflowFlinkPlugin extends AutoPlugin {

  override def requires = CloudflowBasePlugin

  override def projectSettings = Seq(
    cloudflowFlinkBaseImage := None,
    libraryDependencies ++= Vector(
          "com.lightbend.cloudflow" % s"cloudflow-flink_${(ThisProject / scalaBinaryVersion).value}"         % (ThisProject / cloudflowVersion).value,
          "com.lightbend.cloudflow" % s"cloudflow-flink-testkit_${(ThisProject / scalaBinaryVersion).value}" % (ThisProject / cloudflowVersion).value % "test"
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

      val configSh = (ThisProject / target).value / "cloudflow" / "flink" / "config.sh"
      IO.write(configSh, configShContent)

      val flinkConsole = (ThisProject / target).value / "cloudflow" / "flink" / "flink-console.sh"
      IO.write(flinkConsole, flinkConsoleContent)

      val flinkEntrypoint = (ThisProject / target).value / "cloudflow" / "flink" / "flink-entrypoint.sh"
      IO.write(flinkEntrypoint, flinkEntrypointContent)

      val scalaVersion = (ThisProject / scalaBinaryVersion).value
      val flinkVersion = "1.10.0"
      val flinkHome    = "/opt/flink"

      val flinkTgz    = s"lightbend-flink-${flinkVersion}.tgz"
      val flinkTgzUrl = s"https://github.com/lightbend/flink/releases/download/v$flinkVersion-lightbend/$flinkTgz"

      Seq(
        Instructions.Env("FLINK_VERSION", flinkVersion),
        Instructions.Env("SCALA_VERSION", scalaVersion),
        Instructions.Env("FLINK_HOME", flinkHome),
        Instructions.User("root"),
        Instructions.Run.exec(Seq("apk", "add", "curl", "wget", "bash", "snappy-dev", "gettext-dev")),
        Instructions.Run.exec(Seq("wget", flinkTgzUrl)),
        Instructions.Run.exec(Seq("tar", "-xvzf", flinkTgz)),
        Instructions.Run.exec(Seq("mv", s"flink-${flinkVersion}", flinkHome)),
        Instructions.Run.exec(Seq("rm", flinkTgz)),
        Instructions.Run.exec(Seq("addgroup", "-S", "-g", "9999", "flink")),
        Instructions.Run.exec(Seq("adduser", "-S", "-h", flinkHome, "-u", "9999", "flink", "flink")),
        Instructions.Run.exec(Seq("addgroup", "-S", "-g", "185", "cloudflow")),
        Instructions.Run.exec(Seq("adduser", "-u", "185", "-S", "-h", "/home/cloudflow", "-s", "/sbin/nologin", "cloudflow", "root")),
        Instructions.Run.exec(Seq("adduser", "cloudflow", "cloudflow")),
        Instructions.Run.exec(Seq("rm", "-rf", "/var/lib/apt/lists/*")),
        Instructions.Run.exec(Seq("chown", "-R", "flink:flink", "/var")),
        Instructions.Run.exec(Seq("chown", "-R", "flink:root", "/usr/local")),
        Instructions.Run.exec(Seq("chmod", "775", "/usr/local")),
        Instructions.Run.exec(Seq("mkdir", s"${flinkHome}/flink-web-upload")),
        Instructions.Run.exec(
          Seq("mv", s"${flinkHome}/opt/flink-queryable-state-runtime_${scalaVersion}-${flinkVersion}.jar", s"${flinkHome}/lib")
        ),
        Instructions.Run.exec(Seq("mkdir", "-p", "/prometheus")),
        Instructions.Run.exec(
          Seq(
            "curl",
            "https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.11.0/jmx_prometheus_javaagent-0.11.0.jar",
            "-o",
            "/prometheus/jmx_prometheus_javaagent.jar"
          )
        ),
        Instructions.Run.exec(Seq("chmod", "-R", "777", flinkHome)),
        Instructions.Copy(CopyFile(configSh), s"${flinkHome}/bin/config.sh"),
        Instructions.Run.exec(Seq("chmod", "a+x", s"${flinkHome}/bin/config.sh")),
        Instructions.Copy(CopyFile(flinkConsole), s"${flinkHome}/bin/flink-console.sh"),
        Instructions.Run.exec(Seq("chmod", "777", s"${flinkHome}/bin/flink-console.sh")),
        Instructions.Run.exec(Seq("chown", "501:dialout", s"${flinkHome}/bin/flink-console.sh")),
        Instructions.Copy(CopyFile(flinkEntrypoint), "/opt/flink-entrypoint.sh"),
        Instructions.Run.exec(Seq("chmod", "a+x", "/opt/flink-entrypoint.sh")),
        Instructions.EntryPoint.exec(Seq("bash", "/opt/flink-entrypoint.sh")),
        Instructions.User(UserInImage),
        Instructions.Copy(sources = Seq(CopyFile(depJarsDir)), destination = OptAppDir, chown = Some(userAsOwner(UserInImage))),
        Instructions.Copy(sources = Seq(CopyFile(appJarsDir)), destination = OptAppDir, chown = Some(userAsOwner(UserInImage))),
        Instructions.Run(
          s"cp ${OptAppDir}cloudflow-runner_${(ThisProject / scalaBinaryVersion).value}*.jar  /opt/flink/flink-web-upload/cloudflow-runner.jar"
        )
      )
    },
    dockerfile in docker := {
      val log = streams.value.log
      // this triggers side-effects, e.g. files being created in the staging area
      cloudflowStageAppJars.value

      cloudflowFlinkBaseImage.value match {
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
            runRaw(
              s"cp ${OptAppDir}cloudflow-runner_${(ThisProject / scalaBinaryVersion).value}*.jar  /opt/flink/flink-web-upload/cloudflow-runner.jar"
            )
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

  private val configShContent =
    """#!/usr/bin/env bash
      |################################################################################
      |#  Licensed to the Apache Software Foundation (ASF) under one
      |#  or more contributor license agreements.  See the NOTICE file
      |#  distributed with this work for additional information
      |#  regarding copyright ownership.  The ASF licenses this file
      |#  to you under the Apache License, Version 2.0 (the
      |#  "License"); you may not use this file except in compliance
      |#  with the License.  You may obtain a copy of the License at
      |#
      |#      http://www.apache.org/licenses/LICENSE-2.0
      |#
      |#  Unless required by applicable law or agreed to in writing, software
      |#  distributed under the License is distributed on an "AS IS" BASIS,
      |#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
      |#  See the License for the specific language governing permissions and
      |# limitations under the License.
      |################################################################################
      |
      |constructFlinkClassPath() {
      |    local FLINK_DIST
      |    local FLINK_CLASSPATH
      |
      |    while read -d '' -r jarfile ; do
      |        if [[ "$jarfile" =~ .*/flink-dist[^/]*.jar$ ]]; then
      |            FLINK_DIST="$FLINK_DIST":"$jarfile"
      |        elif [[ "$FLINK_CLASSPATH" == "" ]]; then
      |            FLINK_CLASSPATH="$jarfile";
      |        else
      |            FLINK_CLASSPATH="$FLINK_CLASSPATH":"$jarfile"
      |        fi
      |    done < <(find "$FLINK_LIB_DIR" /opt/cloudflow ! -type d ! -name "hadoop-*.jar" -name '*.jar' -print0 | sort -z)
      |
      |    if [[ "$FLINK_DIST" == "" ]]; then
      |        # write error message to stderr since stdout is stored as the classpath
      |        (>&2 echo "[ERROR] Flink distribution jar not found in $FLINK_LIB_DIR.")
      |
      |        # exit function with empty classpath to force process failure
      |        exit 1
      |    fi
      |
      |    echo "$FLINK_CLASSPATH""$FLINK_DIST"
      |}
      |
      |findFlinkDistJar() {
      |    local FLINK_DIST="`find "$FLINK_LIB_DIR" -name 'flink-dist*.jar'`"
      |
      |    if [[ "$FLINK_DIST" == "" ]]; then
      |        # write error message to stderr since stdout is stored as the classpath
      |        (>&2 echo "[ERROR] Flink distribution jar not found in $FLINK_LIB_DIR.")
      |
      |        # exit function with empty classpath to force process failure
      |        exit 1
      |    fi
      |
      |    echo "$FLINK_DIST"
      |}
      |
      |# These are used to mangle paths that are passed to java when using
      |# cygwin. Cygwin paths are like linux paths, i.e. /path/to/somewhere
      |# but the windows java version expects them in Windows Format, i.e. C:\bla\blub.
      |# "cygpath" can do the conversion.
      |manglePath() {
      |    UNAME=$(uname -s)
      |    if [ "${UNAME:0:6}" == "CYGWIN" ]; then
      |        echo `cygpath -w "$1"`
      |    else
      |        echo $1
      |    fi
      |}
      |
      |manglePathList() {
      |    UNAME=$(uname -s)
      |    # a path list, for example a java classpath
      |    if [ "${UNAME:0:6}" == "CYGWIN" ]; then
      |        echo `cygpath -wp "$1"`
      |    else
      |        echo $1
      |    fi
      |}
      |
      |# Looks up a config value by key from a simple YAML-style key-value map.
      |# $1: key to look up
      |# $2: default value to return if key does not exist
      |# $3: config file to read from
      |readFromConfig() {
      |    local key=$1
      |    local defaultValue=$2
      |    local configFile=$3
      |
      |    # first extract the value with the given key (1st sed), then trim the result (2nd sed)
      |    # if a key exists multiple times, take the "last" one (tail)
      |    local value=`sed -n "s/^[ ]*${key}[ ]*: \([^#]*\).*$/\1/p" "${configFile}" | sed "s/^ *//;s/ *$//" | tail -n 1`
      |
      |    [ -z "$value" ] && echo "$defaultValue" || echo "$value"
      |}
      |
      |########################################################################################################################
      |# DEFAULT CONFIG VALUES: These values will be used when nothing has been specified in conf/flink-conf.yaml
      |# -or- the respective environment variables are not set.
      |########################################################################################################################
      |
      |
      |# WARNING !!! , these values are only used if there is nothing else is specified in
      |# conf/flink-conf.yaml
      |
      |DEFAULT_ENV_PID_DIR="/tmp"                          # Directory to store *.pid files to
      |DEFAULT_ENV_LOG_MAX=5                               # Maximum number of old log files to keep
      |DEFAULT_ENV_JAVA_OPTS=""                            # Optional JVM args
      |DEFAULT_ENV_JAVA_OPTS_JM=""                         # Optional JVM args (JobManager)
      |DEFAULT_ENV_JAVA_OPTS_TM=""                         # Optional JVM args (TaskManager)
      |DEFAULT_ENV_JAVA_OPTS_HS=""                         # Optional JVM args (HistoryServer)
      |DEFAULT_ENV_SSH_OPTS=""                             # Optional SSH parameters running in cluster mode
      |DEFAULT_YARN_CONF_DIR=""                            # YARN Configuration Directory, if necessary
      |DEFAULT_HADOOP_CONF_DIR=""                          # Hadoop Configuration Directory, if necessary
      |
      |########################################################################################################################
      |# CONFIG KEYS: The default values can be overwritten by the following keys in conf/flink-conf.yaml
      |########################################################################################################################
      |
      |KEY_JOBM_MEM_SIZE="jobmanager.heap.size"
      |KEY_JOBM_MEM_MB="jobmanager.heap.mb"
      |
      |KEY_TASKM_COMPUTE_NUMA="taskmanager.compute.numa"
      |
      |KEY_ENV_PID_DIR="env.pid.dir"
      |KEY_ENV_LOG_DIR="env.log.dir"
      |KEY_ENV_LOG_MAX="env.log.max"
      |KEY_ENV_YARN_CONF_DIR="env.yarn.conf.dir"
      |KEY_ENV_HADOOP_CONF_DIR="env.hadoop.conf.dir"
      |KEY_ENV_JAVA_HOME="env.java.home"
      |KEY_ENV_JAVA_OPTS="env.java.opts"
      |KEY_ENV_JAVA_OPTS_JM="env.java.opts.jobmanager"
      |KEY_ENV_JAVA_OPTS_TM="env.java.opts.taskmanager"
      |KEY_ENV_JAVA_OPTS_HS="env.java.opts.historyserver"
      |KEY_ENV_SSH_OPTS="env.ssh.opts"
      |KEY_HIGH_AVAILABILITY="high-availability"
      |KEY_ZK_HEAP_MB="zookeeper.heap.mb"
      |
      |########################################################################################################################
      |# MEMORY SIZE UNIT
      |########################################################################################################################
      |
      |BYTES_UNITS=("b" "bytes")
      |KILO_BYTES_UNITS=("k" "kb" "kibibytes")
      |MEGA_BYTES_UNITS=("m" "mb" "mebibytes")
      |GIGA_BYTES_UNITS=("g" "gb" "gibibytes")
      |TERA_BYTES_UNITS=("t" "tb" "tebibytes")
      |
      |hasUnit() {
      |    text=$1
      |
      |    trimmed=$(echo -e "${text}" | tr -d '[:space:]')
      |
      |    if [ -z "$trimmed" -o "$trimmed" == " " ]; then
      |        echo "$trimmed is an empty- or whitespace-only string"
      |        exit 1
      |    fi
      |
      |    len=${#trimmed}
      |    pos=0
      |
      |    while [ $pos -lt $len ]; do
      |        current=${trimmed:pos:1}
      |        if [[ ! $current < '0' ]] && [[ ! $current > '9' ]]; then
      |            let pos+=1
      |        else
      |            break
      |        fi
      |    done
      |
      |    number=${trimmed:0:pos}
      |
      |    unit=${trimmed:$pos}
      |    unit=$(echo -e "${unit}" | tr -d '[:space:]')
      |    unit=$(echo -e "${unit}" | tr '[A-Z]' '[a-z]')
      |
      |    [[ ! -z "$unit" ]]
      |}
      |
      |parseBytes() {
      |    text=$1
      |
      |    trimmed=$(echo -e "${text}" | tr -d '[:space:]')
      |
      |    if [ -z "$trimmed" -o "$trimmed" == " " ]; then
      |        echo "$trimmed is an empty- or whitespace-only string"
      |        exit 1
      |    fi
      |
      |    len=${#trimmed}
      |    pos=0
      |
      |    while [ $pos -lt $len ]; do
      |        current=${trimmed:pos:1}
      |        if [[ ! $current < '0' ]] && [[ ! $current > '9' ]]; then
      |            let pos+=1
      |        else
      |            break
      |        fi
      |    done
      |
      |    number=${trimmed:0:pos}
      |
      |    unit=${trimmed:$pos}
      |    unit=$(echo -e "${unit}" | tr -d '[:space:]')
      |    unit=$(echo -e "${unit}" | tr '[A-Z]' '[a-z]')
      |
      |    if [ -z "$number" ]; then
      |        echo "text does not start with a number"
      |        exit 1
      |    fi
      |
      |    local multiplier
      |    if [ -z "$unit" ]; then
      |        multiplier=1
      |    else
      |        if matchesAny $unit "${BYTES_UNITS[*]}"; then
      |            multiplier=1
      |        elif matchesAny $unit "${KILO_BYTES_UNITS[*]}"; then
      |                multiplier=1024
      |        elif matchesAny $unit "${MEGA_BYTES_UNITS[*]}"; then
      |                multiplier=`expr 1024 \* 1024`
      |        elif matchesAny $unit "${GIGA_BYTES_UNITS[*]}"; then
      |                multiplier=`expr 1024 \* 1024 \* 1024`
      |        elif matchesAny $unit "${TERA_BYTES_UNITS[*]}"; then
      |                multiplier=`expr 1024 \* 1024 \* 1024 \* 1024`
      |        else
      |            echo "[ERROR] Memory size unit $unit does not match any of the recognized units"
      |            exit 1
      |        fi
      |    fi
      |
      |    ((result=$number * $multiplier))
      |
      |    if [ $[result / multiplier] != "$number" ]; then
      |        echo "[ERROR] The value $text cannot be re represented as 64bit number of bytes (numeric overflow)."
      |        exit 1
      |    fi
      |
      |    echo "$result"
      |}
      |
      |matchesAny() {
      |    str=$1
      |    variants=$2
      |
      |    for s in ${variants[*]}; do
      |        if [ $str == $s ]; then
      |            return 0
      |        fi
      |    done
      |
      |    return 1
      |}
      |
      |getKibiBytes() {
      |    bytes=$1
      |    echo "$(($bytes >>10))"
      |}
      |
      |getMebiBytes() {
      |    bytes=$1
      |    echo "$(($bytes >> 20))"
      |}
      |
      |getGibiBytes() {
      |    bytes=$1
      |    echo "$(($bytes >> 30))"
      |}
      |
      |getTebiBytes() {
      |    bytes=$1
      |    echo "$(($bytes >> 40))"
      |}
      |
      |########################################################################################################################
      |# PATHS AND CONFIG
      |########################################################################################################################
      |
      |target="$0"
      |# For the case, the executable has been directly symlinked, figure out
      |# the correct bin path by following its symlink up to an upper bound.
      |# Note: we can't use the readlink utility here if we want to be POSIX
      |# compatible.
      |iteration=0
      |while [ -L "$target" ]; do
      |    if [ "$iteration" -gt 100 ]; then
      |        echo "Cannot resolve path: You have a cyclic symlink in $target."
      |        break
      |    fi
      |    ls=`ls -ld -- "$target"`
      |    target=`expr "$ls" : '.* -> \(.*\)$'`
      |    iteration=$((iteration + 1))
      |done
      |
      |# Convert relative path to absolute path and resolve directory symlinks
      |bin=`dirname "$target"`
      |SYMLINK_RESOLVED_BIN=`cd "$bin"; pwd -P`
      |
      |# Define the main directory of the flink installation
      |# If config.sh is called by pyflink-shell.sh in python bin directory(pip installed), then do not need to set the FLINK_HOME here.
      |if [ -z "$_FLINK_HOME_DETERMINED" ]; then
      |    FLINK_HOME=`dirname "$SYMLINK_RESOLVED_BIN"`
      |fi
      |FLINK_LIB_DIR=$FLINK_HOME/lib
      |FLINK_PLUGINS_DIR=$FLINK_HOME/plugins
      |FLINK_OPT_DIR=$FLINK_HOME/opt
      |
      |
      |# These need to be mangled because they are directly passed to java.
      |# The above lib path is used by the shell script to retrieve jars in a
      |# directory, so it needs to be unmangled.
      |FLINK_HOME_DIR_MANGLED=`manglePath "$FLINK_HOME"`
      |if [ -z "$FLINK_CONF_DIR" ]; then FLINK_CONF_DIR=$FLINK_HOME_DIR_MANGLED/conf; fi
      |FLINK_BIN_DIR=$FLINK_HOME_DIR_MANGLED/bin
      |DEFAULT_FLINK_LOG_DIR=$FLINK_HOME_DIR_MANGLED/log
      |FLINK_CONF_FILE="flink-conf.yaml"
      |YAML_CONF=${FLINK_CONF_DIR}/${FLINK_CONF_FILE}
      |
      |### Exported environment variables ###
      |export FLINK_CONF_DIR
      |export FLINK_BIN_DIR
      |export FLINK_PLUGINS_DIR
      |# export /lib dir to access it during deployment of the Yarn staging files
      |export FLINK_LIB_DIR
      |# export /opt dir to access it for the SQL client
      |export FLINK_OPT_DIR
      |
      |########################################################################################################################
      |# ENVIRONMENT VARIABLES
      |########################################################################################################################
      |
      |# read JAVA_HOME from config with no default value
      |MY_JAVA_HOME=$(readFromConfig ${KEY_ENV_JAVA_HOME} "" "${YAML_CONF}")
      |# check if config specified JAVA_HOME
      |if [ -z "${MY_JAVA_HOME}" ]; then
      |    # config did not specify JAVA_HOME. Use system JAVA_HOME
      |    MY_JAVA_HOME=${JAVA_HOME}
      |fi
      |# check if we have a valid JAVA_HOME and if java is not available
      |if [ -z "${MY_JAVA_HOME}" ] && ! type java > /dev/null 2> /dev/null; then
      |    echo "Please specify JAVA_HOME. Either in Flink config ./conf/flink-conf.yaml or as system-wide JAVA_HOME."
      |    exit 1
      |else
      |    JAVA_HOME=${MY_JAVA_HOME}
      |fi
      |
      |UNAME=$(uname -s)
      |if [ "${UNAME:0:6}" == "CYGWIN" ]; then
      |    JAVA_RUN=java
      |else
      |    if [[ -d $JAVA_HOME ]]; then
      |        JAVA_RUN=$JAVA_HOME/bin/java
      |    else
      |        JAVA_RUN=java
      |    fi
      |fi
      |
      |# Define HOSTNAME if it is not already set
      |if [ -z "${HOSTNAME}" ]; then
      |    HOSTNAME=`hostname`
      |fi
      |
      |IS_NUMBER="^[0-9]+$"
      |
      |# Define FLINK_JM_HEAP if it is not already set
      |if [ -z "${FLINK_JM_HEAP}" ]; then
      |    FLINK_JM_HEAP=$(readFromConfig ${KEY_JOBM_MEM_SIZE} 0 "${YAML_CONF}")
      |fi
      |
      |# Try read old config key, if new key not exists
      |if [ "${FLINK_JM_HEAP}" == 0 ]; then
      |    FLINK_JM_HEAP_MB=$(readFromConfig ${KEY_JOBM_MEM_MB} 0 "${YAML_CONF}")
      |fi
      |
      |# Verify that NUMA tooling is available
      |command -v numactl >/dev/null 2>&1
      |if [[ $? -ne 0 ]]; then
      |    FLINK_TM_COMPUTE_NUMA="false"
      |else
      |    # Define FLINK_TM_COMPUTE_NUMA if it is not already set
      |    if [ -z "${FLINK_TM_COMPUTE_NUMA}" ]; then
      |        FLINK_TM_COMPUTE_NUMA=$(readFromConfig ${KEY_TASKM_COMPUTE_NUMA} "false" "${YAML_CONF}")
      |    fi
      |fi
      |
      |if [ -z "${MAX_LOG_FILE_NUMBER}" ]; then
      |    MAX_LOG_FILE_NUMBER=$(readFromConfig ${KEY_ENV_LOG_MAX} ${DEFAULT_ENV_LOG_MAX} "${YAML_CONF}")
      |fi
      |
      |if [ -z "${FLINK_LOG_DIR}" ]; then
      |    FLINK_LOG_DIR=$(readFromConfig ${KEY_ENV_LOG_DIR} "${DEFAULT_FLINK_LOG_DIR}" "${YAML_CONF}")
      |fi
      |
      |if [ -z "${YARN_CONF_DIR}" ]; then
      |    YARN_CONF_DIR=$(readFromConfig ${KEY_ENV_YARN_CONF_DIR} "${DEFAULT_YARN_CONF_DIR}" "${YAML_CONF}")
      |fi
      |
      |if [ -z "${HADOOP_CONF_DIR}" ]; then
      |    HADOOP_CONF_DIR=$(readFromConfig ${KEY_ENV_HADOOP_CONF_DIR} "${DEFAULT_HADOOP_CONF_DIR}" "${YAML_CONF}")
      |fi
      |
      |if [ -z "${FLINK_PID_DIR}" ]; then
      |    FLINK_PID_DIR=$(readFromConfig ${KEY_ENV_PID_DIR} "${DEFAULT_ENV_PID_DIR}" "${YAML_CONF}")
      |fi
      |
      |if [ -z "${FLINK_ENV_JAVA_OPTS}" ]; then
      |    FLINK_ENV_JAVA_OPTS=$(readFromConfig ${KEY_ENV_JAVA_OPTS} "${DEFAULT_ENV_JAVA_OPTS}" "${YAML_CONF}")
      |
      |    # Remove leading and ending double quotes (if present) of value
      |    FLINK_ENV_JAVA_OPTS="$( echo "${FLINK_ENV_JAVA_OPTS}" | sed -e 's/^"//'  -e 's/"$//' )"
      |fi
      |
      |if [ -z "${FLINK_ENV_JAVA_OPTS_JM}" ]; then
      |    FLINK_ENV_JAVA_OPTS_JM=$(readFromConfig ${KEY_ENV_JAVA_OPTS_JM} "${DEFAULT_ENV_JAVA_OPTS_JM}" "${YAML_CONF}")
      |    # Remove leading and ending double quotes (if present) of value
      |    FLINK_ENV_JAVA_OPTS_JM="$( echo "${FLINK_ENV_JAVA_OPTS_JM}" | sed -e 's/^"//'  -e 's/"$//' )"
      |fi
      |
      |if [ -z "${FLINK_ENV_JAVA_OPTS_TM}" ]; then
      |    FLINK_ENV_JAVA_OPTS_TM=$(readFromConfig ${KEY_ENV_JAVA_OPTS_TM} "${DEFAULT_ENV_JAVA_OPTS_TM}" "${YAML_CONF}")
      |    # Remove leading and ending double quotes (if present) of value
      |    FLINK_ENV_JAVA_OPTS_TM="$( echo "${FLINK_ENV_JAVA_OPTS_TM}" | sed -e 's/^"//'  -e 's/"$//' )"
      |fi
      |
      |if [ -z "${FLINK_ENV_JAVA_OPTS_HS}" ]; then
      |    FLINK_ENV_JAVA_OPTS_HS=$(readFromConfig ${KEY_ENV_JAVA_OPTS_HS} "${DEFAULT_ENV_JAVA_OPTS_HS}" "${YAML_CONF}")
      |    # Remove leading and ending double quotes (if present) of value
      |    FLINK_ENV_JAVA_OPTS_HS="$( echo "${FLINK_ENV_JAVA_OPTS_HS}" | sed -e 's/^"//'  -e 's/"$//' )"
      |fi
      |
      |if [ -z "${FLINK_SSH_OPTS}" ]; then
      |    FLINK_SSH_OPTS=$(readFromConfig ${KEY_ENV_SSH_OPTS} "${DEFAULT_ENV_SSH_OPTS}" "${YAML_CONF}")
      |fi
      |
      |# Define ZK_HEAP if it is not already set
      |if [ -z "${ZK_HEAP}" ]; then
      |    ZK_HEAP=$(readFromConfig ${KEY_ZK_HEAP_MB} 0 "${YAML_CONF}")
      |fi
      |
      |# High availability
      |if [ -z "${HIGH_AVAILABILITY}" ]; then
      |     HIGH_AVAILABILITY=$(readFromConfig ${KEY_HIGH_AVAILABILITY} "" "${YAML_CONF}")
      |     if [ -z "${HIGH_AVAILABILITY}" ]; then
      |        # Try deprecated value
      |        DEPRECATED_HA=$(readFromConfig "recovery.mode" "" "${YAML_CONF}")
      |        if [ -z "${DEPRECATED_HA}" ]; then
      |            HIGH_AVAILABILITY="none"
      |        elif [ ${DEPRECATED_HA} == "standalone" ]; then
      |            # Standalone is now 'none'
      |            HIGH_AVAILABILITY="none"
      |        else
      |            HIGH_AVAILABILITY=${DEPRECATED_HA}
      |        fi
      |     fi
      |fi
      |
      |# Arguments for the JVM. Used for job and task manager JVMs.
      |# DO NOT USE FOR MEMORY SETTINGS! Use conf/flink-conf.yaml with keys
      |# KEY_JOBM_MEM_SIZE and TaskManagerOptions#TOTAL_PROCESS_MEMORY for that!
      |if [ -z "${JVM_ARGS}" ]; then
      |    JVM_ARGS=""
      |fi
      |
      |# Check if deprecated HADOOP_HOME is set, and specify config path to HADOOP_CONF_DIR if it's empty.
      |if [ -z "$HADOOP_CONF_DIR" ]; then
      |    if [ -n "$HADOOP_HOME" ]; then
      |        # HADOOP_HOME is set. Check if its a Hadoop 1.x or 2.x HADOOP_HOME path
      |        if [ -d "$HADOOP_HOME/conf" ]; then
      |            # its a Hadoop 1.x
      |            HADOOP_CONF_DIR="$HADOOP_HOME/conf"
      |        fi
      |        if [ -d "$HADOOP_HOME/etc/hadoop" ]; then
      |            # Its Hadoop 2.2+
      |            HADOOP_CONF_DIR="$HADOOP_HOME/etc/hadoop"
      |        fi
      |    fi
      |fi
      |
      |# try and set HADOOP_CONF_DIR to some common default if it's not set
      |if [ -z "$HADOOP_CONF_DIR" ]; then
      |    if [ -d "/etc/hadoop/conf" ]; then
      |        echo "Setting HADOOP_CONF_DIR=/etc/hadoop/conf because no HADOOP_CONF_DIR was set."
      |        HADOOP_CONF_DIR="/etc/hadoop/conf"
      |    fi
      |fi
      |
      |INTERNAL_HADOOP_CLASSPATHS="${HADOOP_CLASSPATH}:${HADOOP_CONF_DIR}:${YARN_CONF_DIR}"
      |
      |if [ -n "${HBASE_CONF_DIR}" ]; then
      |    INTERNAL_HADOOP_CLASSPATHS="${INTERNAL_HADOOP_CLASSPATHS}:${HBASE_CONF_DIR}"
      |fi
      |
      |# Auxilliary function which extracts the name of host from a line which
      |# also potentially includes topology information and the taskManager type
      |extractHostName() {
      |    # handle comments: extract first part of string (before first # character)
      |    SLAVE=`echo $1 | cut -d'#' -f 1`
      |
      |    # Extract the hostname from the network hierarchy
      |    if [[ "$SLAVE" =~ ^.*/([0-9a-zA-Z.-]+)$ ]]; then
      |            SLAVE=${BASH_REMATCH[1]}
      |    fi
      |
      |    echo $SLAVE
      |}
      |
      |# Auxilliary functions for log file rotation
      |rotateLogFilesWithPrefix() {
      |    dir=$1
      |    prefix=$2
      |    while read -r log ; do
      |        rotateLogFile "$log"
      |    # find distinct set of log file names, ignoring the rotation number (trailing dot and digit)
      |    done < <(find "$dir" ! -type d -path "${prefix}*" | sed s/\.[0-9][0-9]*$// | sort | uniq)
      |}
      |
      |rotateLogFile() {
      |    log=$1;
      |    num=$MAX_LOG_FILE_NUMBER
      |    if [ -f "$log" -a "$num" -gt 0 ]; then
      |        while [ $num -gt 1 ]; do
      |            prev=`expr $num - 1`
      |            [ -f "$log.$prev" ] && mv "$log.$prev" "$log.$num"
      |            num=$prev
      |        done
      |        mv "$log" "$log.$num";
      |    fi
      |}
      |
      |readMasters() {
      |    MASTERS_FILE="${FLINK_CONF_DIR}/masters"
      |
      |    if [[ ! -f "${MASTERS_FILE}" ]]; then
      |        echo "No masters file. Please specify masters in 'conf/masters'."
      |        exit 1
      |    fi
      |
      |    MASTERS=()
      |    WEBUIPORTS=()
      |
      |    MASTERS_ALL_LOCALHOST=true
      |    GOON=true
      |    while $GOON; do
      |        read line || GOON=false
      |        HOSTWEBUIPORT=$( extractHostName $line)
      |
      |        if [ -n "$HOSTWEBUIPORT" ]; then
      |            HOST=$(echo $HOSTWEBUIPORT | cut -f1 -d:)
      |            WEBUIPORT=$(echo $HOSTWEBUIPORT | cut -s -f2 -d:)
      |            MASTERS+=(${HOST})
      |
      |            if [ -z "$WEBUIPORT" ]; then
      |                WEBUIPORTS+=(0)
      |            else
      |                WEBUIPORTS+=(${WEBUIPORT})
      |            fi
      |
      |            if [ "${HOST}" != "localhost" ] && [ "${HOST}" != "127.0.0.1" ] ; then
      |                MASTERS_ALL_LOCALHOST=false
      |            fi
      |        fi
      |    done < "$MASTERS_FILE"
      |}
      |
      |readSlaves() {
      |    SLAVES_FILE="${FLINK_CONF_DIR}/slaves"
      |
      |    if [[ ! -f "$SLAVES_FILE" ]]; then
      |        echo "No slaves file. Please specify slaves in 'conf/slaves'."
      |        exit 1
      |    fi
      |
      |    SLAVES=()
      |
      |    SLAVES_ALL_LOCALHOST=true
      |    GOON=true
      |    while $GOON; do
      |        read line || GOON=false
      |        HOST=$( extractHostName $line)
      |        if [ -n "$HOST" ] ; then
      |            SLAVES+=(${HOST})
      |            if [ "${HOST}" != "localhost" ] && [ "${HOST}" != "127.0.0.1" ] ; then
      |                SLAVES_ALL_LOCALHOST=false
      |            fi
      |        fi
      |    done < "$SLAVES_FILE"
      |}
      |
      |# starts or stops TMs on all slaves
      |# TMSlaves start|stop
      |TMSlaves() {
      |    CMD=$1
      |
      |    readSlaves
      |
      |    if [ ${SLAVES_ALL_LOCALHOST} = true ] ; then
      |        # all-local setup
      |        for slave in ${SLAVES[@]}; do
      |            "${FLINK_BIN_DIR}"/taskmanager.sh "${CMD}"
      |        done
      |    else
      |        # non-local setup
      |        # Stop TaskManager instance(s) using pdsh (Parallel Distributed Shell) when available
      |        command -v pdsh >/dev/null 2>&1
      |        if [[ $? -ne 0 ]]; then
      |            for slave in ${SLAVES[@]}; do
      |                ssh -n $FLINK_SSH_OPTS $slave -- "nohup /bin/bash -l \"${FLINK_BIN_DIR}/taskmanager.sh\" \"${CMD}\" &"
      |            done
      |        else
      |            PDSH_SSH_ARGS="" PDSH_SSH_ARGS_APPEND=$FLINK_SSH_OPTS pdsh -w $(IFS=, ; echo "${SLAVES[*]}") \
      |                "nohup /bin/bash -l \"${FLINK_BIN_DIR}/taskmanager.sh\" \"${CMD}\""
      |        fi
      |    fi
      |}
      |
      |runBashJavaUtilsCmd() {
      |    local cmd=$1
      |    local conf_dir=$2
      |    local class_path="${3:-$FLINK_BIN_DIR/bash-java-utils.jar:`findFlinkDistJar`}"
      |    class_path=`manglePathList ${class_path}`
      |
      |    local output=`${JAVA_RUN} -classpath ${class_path} org.apache.flink.runtime.util.BashJavaUtils ${cmd} --configDir ${conf_dir} 2>&1 | tail -n 1000`
      |    if [[ $? -ne 0 ]]; then
      |        echo "[ERROR] Cannot run BashJavaUtils to execute command ${cmd}." 1>&2
      |        # Print the output in case the user redirect the log to console.
      |        echo "$output" 1>&2
      |        exit 1
      |    fi
      |
      |    echo "$output"
      |}
      |
      |extractExecutionParams() {
      |    local output=$1
      |    local EXECUTION_PREFIX="BASH_JAVA_UTILS_EXEC_RESULT:"
      |
      |    local execution_config=`echo "$output" | tail -n 1`
      |    if ! [[ $execution_config =~ ^${EXECUTION_PREFIX}.* ]]; then
      |        echo "[ERROR] Unexpected result: $execution_config" 1>&2
      |        echo "[ERROR] The last line of the BashJavaUtils outputs is expected to be the execution result, following the prefix '${EXECUTION_PREFIX}'" 1>&2
      |        echo "$output" 1>&2
      |        exit 1
      |    fi
      |
      |    echo ${execution_config} | sed "s/$EXECUTION_PREFIX//"
      |}
      |""".stripMargin

  private val flinkConsoleContent =
    """#!/usr/bin/env bash
      |################################################################################
      |#  Licensed to the Apache Software Foundation (ASF) under one
      |#  or more contributor license agreements.  See the NOTICE file
      |#  distributed with this work for additional information
      |#  regarding copyright ownership.  The ASF licenses this file
      |#  to you under the Apache License, Version 2.0 (the
      |#  "License"); you may not use this file except in compliance
      |#  with the License.  You may obtain a copy of the License at
      |#
      |#      http://www.apache.org/licenses/LICENSE-2.0
      |#
      |#  Unless required by applicable law or agreed to in writing, software
      |#  distributed under the License is distributed on an "AS IS" BASIS,
      |#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
      |#  See the License for the specific language governing permissions and
      |# limitations under the License.
      |################################################################################
      |
      |# Start a Flink service as a console application. Must be stopped with Ctrl-C
      |# or with SIGTERM by kill or the controlling process.
      |USAGE="Usage: flink-console.sh (taskexecutor|zookeeper|historyserver|standalonesession|standalonejob) [args]"
      |
      |SERVICE=$1
      |ARGS=("${@:2}") # get remaining arguments as array
      |
      |bin=`dirname "$0"`
      |bin=`cd "$bin"; pwd`
      |
      |. "$bin"/config.sh
      |
      |case $SERVICE in
      |    (taskexecutor)
      |        CLASS_TO_RUN=org.apache.flink.runtime.taskexecutor.TaskManagerRunner
      |    ;;
      |
      |    (historyserver)
      |        CLASS_TO_RUN=org.apache.flink.runtime.webmonitor.history.HistoryServer
      |    ;;
      |
      |    (zookeeper)
      |        CLASS_TO_RUN=org.apache.flink.runtime.zookeeper.FlinkZooKeeperQuorumPeer
      |    ;;
      |
      |    (standalonesession)
      |        CLASS_TO_RUN=org.apache.flink.runtime.entrypoint.StandaloneSessionClusterEntrypoint
      |    ;;
      |
      |    (standalonejob)
      |        CLASS_TO_RUN=org.apache.flink.container.entrypoint.StandaloneJobClusterEntryPoint
      |    ;;
      |
      |    (*)
      |        echo "Unknown service '${SERVICE}'. $USAGE."
      |        exit 1
      |    ;;
      |esac
      |
      |FLINK_TM_CLASSPATH=`constructFlinkClassPath`
      |
      |log_setting=("-Dlog4j.configuration=file:${FLINK_CONF_DIR}/log4j-console.properties" "-Dlogback.configurationFile=file:${FLINK_CONF_DIR}/logback-console.xml")
      |
      |JAVA_VERSION=$(${JAVA_RUN} -version 2>&1 | sed 's/.*version "\(.*\)\.\(.*\)\..*"/\1\2/; 1q')
      |
      |# Only set JVM 8 arguments if we have correctly extracted the version
      |if [[ ${JAVA_VERSION} =~ ${IS_NUMBER} ]]; then
      |    if [ "$JAVA_VERSION" -lt 18 ]; then
      |        JVM_ARGS="$JVM_ARGS -XX:MaxPermSize=256m"
      |    fi
      |fi
      |
      |echo "Starting $SERVICE as a console application on host $HOSTNAME."
      |exec $JAVA_RUN $JVM_ARGS ${FLINK_ENV_JAVA_OPTS} "${log_setting[@]}" -classpath "`manglePathList "$FLINK_TM_CLASSPATH:$INTERNAL_HADOOP_CLASSPATHS"`" ${CLASS_TO_RUN} "${ARGS[@]}"
      |""".stripMargin

  private val flinkEntrypointContent =
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
      |drop_privs_cmd() {
      |    if [ $(id -u) != 0 ]; then
      |        # Don't need to drop privs if EUID != 0
      |        return
      |    elif [ -x /sbin/su-exec ]; then
      |        # Alpine
      |        echo su-exec
      |    else
      |        # Others
      |        echo gosu flink
      |    fi
      |}
      |
      |# Add in extra configs set by the operator
      |if [ -n "$FLINK_PROPERTIES" ]; then
      |    echo "$FLINK_PROPERTIES" >> $FLINK_HOME/flink-conf-tmp.yaml
      |fi
      |
      |export FLINK_PROMETHEUS_JMX_JAVA_OPTS="-javaagent:/prometheus/jmx_prometheus_javaagent.jar=2050:/etc/cloudflow-runner/prometheus.yaml"
      |export FLINK_ENV_JAVA_OPTS="${FLINK_ENV_JAVA_OPTS} ${FLINK_PROMETHEUS_JMX_JAVA_OPTS}"
      |
      |if [ -f "$FLINK_HOME/flink-conf-tmp.yaml" ]; then
      |    envsubst < $FLINK_HOME/flink-conf-tmp.yaml > $FLINK_HOME/conf/flink-conf.yaml
      |fi
      |echo "web.upload.dir: $FLINK_HOME" >> "$FLINK_HOME/conf/flink-conf.yaml"
      |echo "jobmanager.web.upload.dir: $FLINK_HOME" >> "$FLINK_HOME/conf/flink-conf.yaml"
      |
      |echo "taskmanager.memory.flink.size: 1024mb" >> "$FLINK_HOME/conf/flink-conf.yaml"
      |
      |# Add JMX metric reporter to config
      |echo "metrics.reporters: jmx" >> "$FLINK_HOME/conf/flink-conf.yaml"
      |
      |# Flink 1.9 JMX config
      |# https://ci.apache.org/projects/flink/flink-docs-release-1.9/monitoring/metrics.html#jmx-orgapacheflinkmetricsjmxjmxreporter
      |echo "metrics.reporter.jmx.factory.class: org.apache.flink.metrics.jmx.JMXReporterFactory" >> "$FLINK_HOME/conf/flink-conf.yaml"
      |# Flink 1.8 JMX config
      |# https://ci.apache.org/projects/flink/flink-docs-release-1.8/monitoring/metrics.html#jmx-orgapacheflinkmetricsjmxjmxreporter
      |echo "metrics.reporter.jmx.class: org.apache.flink.metrics.jmx.JMXReporter" >> "$FLINK_HOME/conf/flink-conf.yaml"
      |
      |COMMAND=$@
      |
      |if [ $# -lt 1 ]; then
      |    COMMAND="local"
      |fi
      |
      |if [ "$COMMAND" = "help" ]; then
      |    echo "Usage: $(basename "$0") (jobmanager|taskmanager|local|help)"
      |    exit 0
      |elif [ "$FLINK_DEPLOYMENT_TYPE" = "jobmanager" ]; then
      |    echo "Starting Job Manager"
      |    echo "config file: " && grep '^[^\n#]' "$FLINK_HOME/conf/flink-conf.yaml"
      |    exec $(drop_privs_cmd) "$FLINK_HOME/bin/jobmanager.sh" start-foreground
      |elif [ "$FLINK_DEPLOYMENT_TYPE" = "taskmanager" ]; then
      |    echo "Starting Task Manager"
      |    echo "config file: " && grep '^[^\n#]' "$FLINK_HOME/conf/flink-conf.yaml"
      |    exec $(drop_privs_cmd) "$FLINK_HOME/bin/taskmanager.sh" start-foreground
      |elif [ "$COMMAND" = "local" ]; then
      |    echo "Starting local cluster"
      |    exec $(drop_privs_cmd) "$FLINK_HOME/bin/jobmanager.sh" start-foreground local
      |fi
      |
      |exec "$@"
      |""".stripMargin

}

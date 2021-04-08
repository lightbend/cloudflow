/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
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

package cloudflow.operator

import akka.actor._
import cloudflow.operator.action._
import cloudflow.operator.action.runner._
import com.typesafe.config._
import io.fabric8.kubernetes.api.model.Quantity

import scala.io.{ BufferedSource, Source }

object Settings extends ExtensionId[Settings] with ExtensionIdProvider {
  override def lookup = Settings
  override def createExtension(system: ExtendedActorSystem) = new Settings(system.settings.config)

  override def apply(system: ActorSystem) =
    new Settings(system.settings.config)
  // TODO change to cloudflow.operator
  val root = "cloudflow.platform"

  private def getNonEmptyString(config: Config, key: String) = {
    val value = config.getString(key).trim()
    if (value.nonEmpty) value
    else throw new ConfigException.BadValue(key, s"Should be a non-empty String")
  }

  private def getOptionalString(config: Config, key: String) = {
    try {
      Some(config.getString(key).trim())
    } catch {
      case _: ConfigException.Missing => None
    }
  }

  private def getPort(config: Config, key: String) =
    validatePortnumber(config.getInt(key), key)

  private def validatePortnumber(port: Int, key: String) =
    if (port >= 0 && port <= 65535) port
    else throw new ConfigException.BadValue(key, s"Not a valid port number: $port")

  private def getResourceConstraints(config: Config): ResourceConstraints =
    ResourceConstraints(
      Quantity.parse(getNonEmptyString(config, "requests-cpu")),
      Quantity.parse(getNonEmptyString(config, "requests-memory")),
      getOptionalString(config, "limits-cpu").map(v => Quantity.parse(v)),
      getOptionalString(config, "limits-memory").map(v => Quantity.parse(v)))
  private def getSparkPodDefaults(config: Config): SparkPodDefaults =
    SparkPodDefaults(
      getOptionalString(config, "requests-cpu").map(v => Quantity.parse(v)),
      getOptionalString(config, "requests-memory").map(v => Quantity.parse(v)),
      getOptionalString(config, "limits-cpu").map(v => Quantity.parse(v)),
      getOptionalString(config, "memory-overhead").map(v => Quantity.parse(v)),
      getOptionalString(config, "java-opts"))
  private def getFlinkPodResourceDefaults(config: Config): FlinkPodResourceDefaults =
    FlinkPodResourceDefaults(
      getOptionalString(config, "requests-cpu").map(v => Quantity.parse(v)),
      getOptionalString(config, "requests-memory").map(v => Quantity.parse(v)),
      getOptionalString(config, "limits-cpu").map(v => Quantity.parse(v)),
      getOptionalString(config, "limits-memory").map(v => Quantity.parse(v)))

  private def getAkkaRunnerDefaults(config: Config, runnerPath: String, runnerStr: String): AkkaRunnerDefaults = {
    val runnerConfig = config.getConfig(runnerPath)
    AkkaRunnerDefaults(
      getResourceConstraints(runnerConfig),
      getNonEmptyString(runnerConfig, "java-opts"),
      getPrometheusRules(runnerStr))
  }

  private def getSparkRunnerDefaults(config: Config, root: String, runnerStr: String): SparkRunnerDefaults = {
    val driverPath = s"$root.deployment.spark-runner-driver"
    val executorPath = s"$root.deployment.spark-runner-executor"

    val driverConfig = config.getConfig(driverPath)
    val executorConfig = config.getConfig(executorPath)

    SparkRunnerDefaults(
      getSparkPodDefaults(driverConfig),
      getSparkPodDefaults(executorConfig),
      getPrometheusRules(runnerStr))
  }

  private def getFlinkRunnerDefaults(config: Config, root: String, runnerStr: String): FlinkRunnerDefaults = {
    val flinkRunnerConfig = config.getConfig(s"$root.deployment.flink-runner")

    val jobManagerConfig = flinkRunnerConfig.getConfig("jobmanager")
    val taskManagerConfig = flinkRunnerConfig.getConfig("taskmanager")
    val parallelism = flinkRunnerConfig.getInt("parallelism")

    FlinkRunnerDefaults(
      parallelism,
      FlinkJobManagerDefaults(jobManagerConfig.getInt("replicas"), getFlinkPodResourceDefaults(jobManagerConfig)),
      FlinkTaskManagerDefaults(taskManagerConfig.getInt("task-slots"), getFlinkPodResourceDefaults(taskManagerConfig)),
      getPrometheusRules(runnerStr))
  }

  def getPrometheusRules(runnerStr: String): String = runnerStr match {
    case AkkaRunner.Runtime =>
      appendResourcesToString("prometheus-rules/base.yaml", "prometheus-rules/kafka-client.yaml")
    case SparkRunner.Runtime =>
      appendResourcesToString(
        "prometheus-rules/base.yaml",
        "prometheus-rules/spark.yaml",
        "prometheus-rules/kafka-client.yaml")
    case FlinkRunner.Runtime =>
      appendResourcesToString(
        "prometheus-rules/base.yaml",
        "prometheus-rules/flink.yaml",
        "prometheus-rules/kafka-client.yaml")
  }

  private def appendResourcesToString(paths: String*): String =
    paths.foldLeft("") {
      case (acc, path) =>
        var source: BufferedSource = null
        try {
          source = Source.fromResource(path)
          acc + source.getLines().mkString("\n") + "\n"
        } catch {
          case t: Throwable => throw new Exception(s"Could not load file from resources with path $path", t)
        } finally {
          source.close()
        }
    }
}

final case class Settings(config: Config) extends Extension {
  import Settings._

  val releaseVersion = getNonEmptyString(config, s"$root.release-version")
  val podName = getNonEmptyString(config, s"$root.pod-name")
  val podNamespace = getNonEmptyString(config, s"$root.pod-namespace")

  val akkaRunnerSettings = getAkkaRunnerDefaults(config, s"$root.deployment.akka-runner", AkkaRunner.Runtime)
  val sparkRunnerSettings = getSparkRunnerDefaults(config, root, SparkRunner.Runtime)
  val flinkRunnerSettings = getFlinkRunnerDefaults(config, root, FlinkRunner.Runtime)

  val flinkEnabled = config.getBoolean(s"$root.flink-enabled")

  val api = ApiSettings(getNonEmptyString(config, s"$root.api.bind-interface"), getPort(config, s"$root.api.bind-port"))

  val deploymentContext = {
    DeploymentContext(akkaRunnerSettings, sparkRunnerSettings, flinkRunnerSettings, podName, podNamespace)
  }
}

final case class ApiSettings(bindInterface: String, bindPort: Int)

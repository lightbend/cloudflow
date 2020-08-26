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

package cloudflow.operator

import scala.io.{ BufferedSource, Source }
import akka.actor._
import com.typesafe.config._
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import skuber.Resource.Quantity

object Settings extends ExtensionId[Settings] with ExtensionIdProvider {
  override def lookup                                       = Settings
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

  private def getPort(config: Config, key: String) =
    validatePortnumber(config.getInt(key), key)

  private def validatePortnumber(port: Int, key: String) =
    if (port >= 0 && port <= 65535) port
    else throw new ConfigException.BadValue(key, s"Not a valid port number: $port")

  private def getPartitionsPerTopic(config: Config, key: String): Int = {

    def validatePartitionsPerTopic(partitionsPerTopic: Int) =
      if (partitionsPerTopic >= 1) partitionsPerTopic
      else throw new ConfigException.BadValue(key, s"Partition count has to be a positive number > 0: $partitionsPerTopic")

    validatePartitionsPerTopic(config.getInt(key))
  }

  private def getReplicationFactor(config: Config, key: String): Int = {

    def validateReplicationFactor(replicationFactor: Int) =
      if (replicationFactor >= 1) replicationFactor
      else throw new ConfigException.BadValue(key, s"Replica count has to be a positive number > 0: $replicationFactor")

    validateReplicationFactor(config.getInt(key))
  }

  private def getResourceConstraints(config: Config): ResourceConstraints = ResourceConstraints(
    getNonEmptyString(config, "requests-cpu"),
    getNonEmptyString(config, "requests-memory"),
    config.as[Option[String]]("limits-cpu").map(v ⇒ Quantity(v)),
    config.as[Option[String]]("limits-memory").map(v ⇒ Quantity(v))
  )
  private def getSparkPodSettings(config: Config): SparkPodSettings = SparkPodSettings(
    config.as[Option[String]]("requests-cpu").map(v ⇒ Quantity(v)),
    config.as[Option[String]]("requests-memory").map(v ⇒ Quantity(v)),
    config.as[Option[String]]("limits-cpu").map(v ⇒ Quantity(v)),
    config.as[Option[String]]("memory-overhead").map(v ⇒ Quantity(v)),
    config.as[Option[String]]("java-opts")
  )
  private def getFlinkPodResourceSettings(config: Config): FlinkPodResourceSettings = FlinkPodResourceSettings(
    config.as[Option[String]]("requests-cpu").map(v ⇒ Quantity(v)),
    config.as[Option[String]]("requests-memory").map(v ⇒ Quantity(v)),
    config.as[Option[String]]("limits-cpu").map(v ⇒ Quantity(v)),
    config.as[Option[String]]("limits-memory").map(v ⇒ Quantity(v))
  )

  private def getAkkaRunnerSettings(config: Config, runnerPath: String, runnerStr: String): AkkaRunnerSettings = {
    val runnerConfig = config.getConfig(runnerPath)
    AkkaRunnerSettings(
      getResourceConstraints(runnerConfig),
      getNonEmptyString(runnerConfig, "java-opts"),
      getPrometheusRules(runnerStr)
    )
  }

  private def getSparkRunnerSettings(config: Config, root: String, runnerStr: String): SparkRunnerSettings = {
    val driverPath   = s"$root.deployment.spark-runner-driver"
    val executorPath = s"$root.deployment.spark-runner-executor"

    val driverConfig   = config.getConfig(driverPath)
    val executorConfig = config.getConfig(executorPath)

    SparkRunnerSettings(
      getSparkPodSettings(driverConfig),
      getSparkPodSettings(executorConfig),
      getPrometheusRules(runnerStr)
    )
  }

  private def getFlinkRunnerSettings(config: Config, root: String, runnerStr: String): FlinkRunnerSettings = {
    val flinkRunnerConfig = config.getConfig(s"$root.deployment.flink-runner")

    val jobManagerConfig  = flinkRunnerConfig.getConfig("jobmanager")
    val taskManagerConfig = flinkRunnerConfig.getConfig("taskmanager")
    val parallelism       = flinkRunnerConfig.as[Int]("parallelism")

    FlinkRunnerSettings(
      parallelism,
      FlinkJobManagerSettings(jobManagerConfig.as[Int]("replicas"), getFlinkPodResourceSettings(jobManagerConfig)),
      FlinkTaskManagerSettings(taskManagerConfig.as[Int]("task-slots"), getFlinkPodResourceSettings(taskManagerConfig)),
      getPrometheusRules(runnerStr)
    )
  }

  def getPrometheusRules(runnerStr: String): String = runnerStr match {
    case runner.AkkaRunner.runtime ⇒
      appendResourcesToString(
        "prometheus-rules/base.yaml",
        "prometheus-rules/kafka-client.yaml"
      )
    case runner.SparkRunner.runtime ⇒
      appendResourcesToString(
        "prometheus-rules/base.yaml",
        "prometheus-rules/spark.yaml",
        "prometheus-rules/kafka-client.yaml"
      )
    case runner.FlinkRunner.runtime ⇒
      appendResourcesToString(
        "prometheus-rules/base.yaml",
        "prometheus-rules/flink.yaml",
        "prometheus-rules/kafka-client.yaml"
      )
  }

  private def appendResourcesToString(paths: String*): String =
    paths.foldLeft("") {
      case (acc, path) ⇒
        var source: BufferedSource = null
        try {
          source = Source.fromResource(path)
          acc + source.getLines.mkString("\n") + "\n"
        } catch {
          case t: Throwable ⇒ throw new Exception(s"Could not load file from resources with path $path", t)
        } finally {
          source.close()
        }
    }
}

final case class Settings(config: Config) extends Extension {
  import Settings._

  val partitionsPerTopic = getPartitionsPerTopic(config, s"$root.kafka.partitions-per-topic")
  val replicationFactor  = getReplicationFactor(config, s"$root.kafka.replication-factor")

  val releaseVersion = getNonEmptyString(config, s"$root.release-version")
  val podName        = getNonEmptyString(config, s"$root.pod-name")
  val podNamespace   = getNonEmptyString(config, s"$root.pod-namespace")

  val kafka = KafkaSettings(
    getNonEmptyString(config, s"$root.kafka.bootstrap-servers"),
    partitionsPerTopic,
    replicationFactor,
    config.as[Option[String]](s"$root.kafka.strimzi-topic-operator-namespace"),
    config.as[Option[String]](s"$root.kafka.strimzi-cluster-name")
  )

  val akkaRunnerSettings        = getAkkaRunnerSettings(config, s"$root.deployment.akka-runner", runner.AkkaRunner.runtime)
  val sparkRunnerSettings       = getSparkRunnerSettings(config, root, runner.SparkRunner.runtime)
  val flinkRunnerSettings       = getFlinkRunnerSettings(config, root, runner.FlinkRunner.runtime)
  val persistentStorageSettings = config.as[PersistentStorageSettings](s"$root.deployment.persistent-storage")

  val api = ApiSettings(
    getNonEmptyString(config, s"$root.api.bind-interface"),
    getPort(config, s"$root.api.bind-port")
  )

  val deploymentContext = {
    DeploymentContext(
      KafkaContext(
        kafka.bootstrapServers,
        kafka.partitionsPerTopic,
        kafka.replicationFactor,
        ConfigFactory.empty()
      ),
      akkaRunnerSettings,
      sparkRunnerSettings,
      flinkRunnerSettings,
      persistentStorageSettings,
      podName,
      podNamespace
    )
  }
}

final case class KafkaSettings(
    bootstrapServers: String,
    partitionsPerTopic: Int,
    replicationFactor: Int,
    strimziTopicOperatorNamespace: Option[String] = None,
    strimziClusterName: Option[String] = None
)

final case class Resources(request: String, limit: String)

final case class PersistentStorageSettings(resources: Resources, storageClassName: String)
final case class ApiSettings(bindInterface: String, bindPort: Int)

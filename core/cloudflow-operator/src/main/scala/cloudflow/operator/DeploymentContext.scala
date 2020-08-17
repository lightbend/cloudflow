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

import skuber.Resource._

/**
 * Provides contextual information for deployment.
 * TODO it is possible that a lot of these settings will come from the client,
 * and in many cases, will be defined per streamlet.
 */
case class DeploymentContext(kafkaContext: KafkaContext,
                             akkaRunnerSettings: AkkaRunnerSettings,
                             sparkRunnerSettings: SparkRunnerSettings,
                             flinkRunnerSettings: FlinkRunnerSettings,
                             persistentStorageSettings: PersistentStorageSettings,
                             podName: String,
                             podNamespace: String) {
  import kafkaContext._
  def infoMessage = s"""
   | pod-name:                         ${podName}
   | pod-namespace                     ${podNamespace}
   |
   | kafka-bootstrap-servers:          ${bootstrapServers}
   | strimzi-cluster-name:             ${strimziClusterName}
   | strimzi-topic-operator-namespace: ${strimziTopicOperatorNamespace}
  """
}

case class KafkaContext(
    bootstrapServers: String,
    partitionsPerTopic: Int,
    replicationFactor: Int,
    strimziTopicOperatorNamespace: Option[String],
    strimziClusterName: Option[String]
) {
  def useStrimzi = strimziTopicOperatorNamespace.nonEmpty && strimziClusterName.nonEmpty
}

final case class Host(name: String, port: Option[Int]) {
  override def toString = s"""$name:${port.getOrElse(80)}"""
}

final case class DockerRegistrySettings(
    host: Host,
    repository: String
)

sealed trait RunnerSettings {
  def prometheusRules: String
}

final case class AkkaRunnerSettings(
    resourceConstraints: ResourceConstraints,
    javaOptions: String,
    prometheusRules: String
) extends RunnerSettings

final case class ResourceConstraints(
    cpuRequests: Quantity,
    memoryRequests: Quantity,
    cpuLimits: Option[Quantity],
    memoryLimits: Option[Quantity]
)

final case class SparkPodSettings(
    cores: Option[Quantity],
    memory: Option[Quantity],
    coreLimit: Option[Quantity],
    memoryOverhead: Option[Quantity],
    javaOptions: Option[String]
)

final case class SparkRunnerSettings(
    driverSettings: SparkPodSettings,
    executorSettings: SparkPodSettings,
    prometheusRules: String
) extends RunnerSettings

final case class FlinkPodResourceSettings(
    cpuRequest: Option[Quantity] = None,
    memoryRequest: Option[Quantity] = None,
    cpuLimit: Option[Quantity] = None,
    memoryLimit: Option[Quantity] = None
)

final case class FlinkJobManagerSettings(
    replicas: Int,
    resources: FlinkPodResourceSettings
)

final case class FlinkTaskManagerSettings(
    taskSlots: Int,
    resources: FlinkPodResourceSettings
)

final case class FlinkRunnerSettings(
    parallelism: Int,
    jobManagerSettings: FlinkJobManagerSettings,
    taskManagerSettings: FlinkTaskManagerSettings,
    prometheusRules: String
) extends RunnerSettings

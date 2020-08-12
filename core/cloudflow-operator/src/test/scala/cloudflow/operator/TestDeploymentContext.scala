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

import skuber.Resource.Quantity

trait TestDeploymentContext {
  implicit val ctx: DeploymentContext =
    DeploymentContext(
      kafkaContext = KafkaContext(
        strimziClusterName = Some("kafka"),
        strimziTopicOperatorNamespace = Some("strimzi"),
        bootstrapServers = "localhost:9092",
        partitionsPerTopic = 3,
        replicationFactor = 1
      ),
      akkaRunnerSettings = AkkaRunnerSettings(
        resourceConstraints = ResourceConstraints(
          cpuRequests = Quantity("100m"),
          memoryRequests = Quantity("128m"),
          cpuLimits = Some(Quantity("1")),
          memoryLimits = Some(Quantity("512m"))
        ),
        javaOptions = "-Xmx1024",
        "(prometheus rules)"
      ),
      sparkRunnerSettings = SparkRunnerSettings(
        driverSettings = SparkPodSettings(
          cores = Some(1),
          memory = Some("512m"),
          coreLimit = Some("1"),
          memoryOverhead = Some("0.1"),
          javaOptions = Some("-Xmx1024")
        ),
        executorSettings = SparkPodSettings(
          cores = Some(1),
          memory = Some("512m"),
          coreLimit = Some("1"),
          memoryOverhead = Some("1024m"),
          javaOptions = Some("-Xmx1024")
        ),
        "(prometheus rules)"
      ),
      flinkRunnerSettings = FlinkRunnerSettings(
        2,
        jobManagerSettings = FlinkJobManagerSettings(1,
                                                     FlinkPodResourceSettings(
                                                       cpuRequest = Some("0.2"),
                                                       memoryRequest = Some("512m"),
                                                       cpuLimit = Some("1"),
                                                       memoryLimit = Some("0.1")
                                                     )),
        taskManagerSettings = FlinkTaskManagerSettings(2,
                                                       FlinkPodResourceSettings(
                                                         cpuRequest = Some("0.2"),
                                                         memoryRequest = Some("512m"),
                                                         cpuLimit = Some("1"),
                                                         memoryLimit = Some("1024m")
                                                       )),
        "(prometheus rules)"
      ),
      PersistentStorageSettings(
        resources = Resources("1G", "2G"),
        storageClassName = "storage"
      ),
      podName = "cloudflow-operator",
      podNamespace = "cloudflow"
    )
}

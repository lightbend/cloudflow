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

package cloudflow.blueprint.deployment

import collection.JavaConverters._

import com.typesafe.config.ConfigFactory
import org.scalatest._

class RunnerConfigSpec extends WordSpec with MustMatchers with OptionValues with Inspectors {

  "a RunnerConfig" should {
    "generate the correct JSON (one streamlet per deployment)" in {
      val runnerConfig = RunnerConfig(appId, appVersion, ingressDeployment, kafkaBootstrapServers)
      val config = ConfigFactory.parseString(runnerConfig.data)

      val streamlets = config.getConfigList("cloudflow.runner.streamlets").asScala
      streamlets.size mustBe 1

      forExactly(1, streamlets) { streamlet ⇒
        streamlet.getString("class_name") mustBe ingressDeployment.className
        streamlet.getString("streamlet_ref") mustBe ingressDeployment.streamletName

        val streamletContext = streamlet.getConfig("context")

        streamletContext.getString("app_id") mustBe appId
        streamletContext.getString("app_version") mustBe appVersion

        val connectedPorts = streamletContext.getConfigList("connected_ports").asScala
        connectedPorts must have size 1

        forExactly(1, connectedPorts) { connectedPort ⇒
          val savepointConfig = connectedPort.getConfig("savepoint_path")

          ingressDeployment.portMappings must contain((
            connectedPort.getString("port"),
            Savepoint(
              savepointConfig.getString("app_id"),
              savepointConfig.getString("streamlet_ref"),
              savepointConfig.getString("port_name")
            )
          ))
        }

        streamletContext.getConfig(s"config") mustBe ingressDeployment.config
      }
    }

    "generate the correct JSON (multiple streamlets per deployment)" in {
      val runnerConfig = RunnerConfig(appId, appVersion, Vector(ingressDeployment, processorDeployment), kafkaBootstrapServers)
      val config = ConfigFactory.parseString(runnerConfig.data)

      val streamlets = config.getConfigList("cloudflow.runner.streamlets").asScala
      streamlets.size mustBe 2

      forExactly(1, streamlets) { streamlet ⇒
        streamlet.getString("class_name") mustBe processorDeployment.className
        streamlet.getString("streamlet_ref") mustBe processorDeployment.streamletName

        val streamletContext = streamlet.getConfig("context")

        streamletContext.getString("app_id") mustBe appId
        streamletContext.getString("app_version") mustBe appVersion

        val connectedPorts = streamletContext.getConfigList("connected_ports").asScala.toList
        connectedPorts must have size 2

        forExactly(2, connectedPorts) { connectedPort ⇒
          val savepointConfig = connectedPort.getConfig("savepoint_path")
          val portName = connectedPort.getString("port")
          val savepoint = Savepoint(
            savepointConfig.getString("app_id"),
            savepointConfig.getString("streamlet_ref"),
            savepointConfig.getString("port_name")
          )

          forExactly(1, allDeployments) { deployment ⇒
            deployment.portMappings must contain(portName -> savepoint)
          }
        }

        streamletContext.getConfig(s"config") mustBe ConfigFactory.empty
      }
    }
  }

  import cloudflow.blueprint._
  import BlueprintBuilder._

  case class Foo(name: String)
  case class Bar(name: String)

  val appId = "monstrous-mite-12345"
  val appVersion = "42-abcdef0"

  val agentPaths = Map(ApplicationDescriptor.PrometheusAgentKey -> "/app/prometheus/prometheus.jar")
  val kafkaBootstrapServers = "kafka-0.broker.kafka.svc.cluster.local:9092,kafka-1.broker.kafka.svc.cluster.local:9092,kafka-2.broker.kafka.svc.cluster.local:9092"

  val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
  val processor = randomStreamlet().asProcessor[Foo, Bar].withRuntime("spark")

  val ingressRef = ingress.ref("ingress")
  val processorRef = processor.ref("processor")

  val blueprint = Blueprint()
    .define(Vector(ingress, processor))
    .use(ingressRef)
    .use(processorRef)
    .connect(ingressRef.out, processorRef.in)

  val verifiedBlueprint = blueprint.verified.right.get
  val descriptor = ApplicationDescriptor(appId, appVersion, verifiedBlueprint, agentPaths)

  val allDeployments = descriptor.deployments
  val ingressDeployment = allDeployments.find(_.streamletName == ingressRef.name).value
  val processorDeployment = allDeployments.find(_.streamletName == processorRef.name).value
}

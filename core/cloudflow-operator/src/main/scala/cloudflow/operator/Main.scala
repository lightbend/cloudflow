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

import java.lang.management.ManagementFactory

import akka.actor._
import akka.stream._
import com.typesafe.config.{ Config, ConfigRenderOptions }
import skuber._
import skuber.api.Configuration
import scala.concurrent.Await
import scala.concurrent.duration._
import skuber.apiextensions._
import skuber.json.format._

import scala.collection.JavaConverters._
import scala.concurrent._
import scala.concurrent.duration._

object Main extends {

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem()

    try {
      implicit val mat = createMaterializer()
      implicit val ec  = system.dispatcher
      val settings     = Settings(system)
      implicit val ctx = settings.deploymentContext

      logStartOperatorMessage(settings)

      HealthChecks.serve(settings)

      val client          = connectToKubernetes()
      val ownerReferences = getPodOwnerReferences(settings, client)
      installProtocolVersion(client.usingNamespace(settings.podNamespace), ownerReferences)
      installCRD(client)

      Operator.handleAppEvents(client)
      Operator.handleConfigurationUpdates(client)
      Operator.handleStatusUpdates(client)
    } catch {
      case t: Throwable ⇒
        system.log.error(t, "Unexpected error starting cloudflow operator, terminating.")
        system.registerOnTermination(exitWithFailure)
        system.terminate()
    }
  }

  private def logStartOperatorMessage(settings: Settings)(implicit system: ActorSystem) = {

    val blockingIODispatcherConfig = system.settings.config.getConfig("akka.actor.default-blocking-io-dispatcher")
    val dispatcherConfig           = system.settings.config.getConfig("akka.actor.default-dispatcher")
    val deploymentConfig           = system.settings.config.getConfig("akka.actor.deployment")

    system.log.info(s"""
      |Started cloudflow operator ..
      |\n${box("Build Info")}
      |${formatBuildInfo}
      |\n${box("JVM Resources")}
      |${getJVMRuntimeParameters}
      |\n${box("Akka Deployment Config")}
      |\n${prettyPrintConfig(deploymentConfig)}
      |\n${box("Akka Default Blocking IO Dispatcher Config")}
      |\n${prettyPrintConfig(blockingIODispatcherConfig)}
      |\n${box("Akka Default Dispatcher Config")}
      |\n${prettyPrintConfig(dispatcherConfig)}
      |\n${box("GC Type")}
      |\n${getGCInfo}
      |\n${box("Cloudflow Context")}
      |${settings.deploymentContext.infoMessage}
      |\n${box("Deployment")}
      |${formatDeploymentInfo(settings)}
      """.stripMargin)
  }

  private def getPodOwnerReferences(settings: Settings, client: skuber.api.client.KubernetesClient)(implicit ec: ExecutionContext) =
    CloudflowOwnerReferences(
      Await.result(client.get[Pod](settings.podName).map(pod => pod.metadata.ownerReferences), 10 seconds)
    )

  private def connectToKubernetes()(implicit system: ActorSystem, mat: Materializer) = {
    val conf   = Configuration.defaultK8sConfig
    val client = k8sInit(conf).usingNamespace("")
    system.log.info(s"Connected to Kubernetes cluster: ${conf.currentContext.cluster.server}")
    client
  }

  private def exitWithFailure() = System.exit(-1)

  private def createMaterializer()(implicit system: ActorSystem) = {
    val decider: Supervision.Decider = {
      case _ ⇒ Supervision.Stop
    }
    ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))
  }

  private def installCRD(client: skuber.api.client.KubernetesClient)(implicit ec: ExecutionContext): Unit = {
    val crdTimeout = 20.seconds
    // TODO check if version is the same, if not, also create.
    Await.ready(
      client.getOption[CustomResourceDefinition](CloudflowApplication.CRD.name).map { result ⇒
        result.fold(client.create(CloudflowApplication.CRD)) { crd ⇒
          if (crd.spec.version != CloudflowApplication.CRD.spec.version) {
            client.create(CloudflowApplication.CRD)
          } else {
            Future.successful(crd)
          }
        }
      },
      crdTimeout
    )
  }

  private def installProtocolVersion(client: skuber.api.client.KubernetesClient,
                                     ownerReferences: CloudflowOwnerReferences)(implicit ec: ExecutionContext): Unit = {
    val protocolVersionTimeout = 20.seconds
    Await.ready(
      client.getOption[ConfigMap](Operator.ProtocolVersionConfigMapName).map {
        _.fold(client.create(Operator.ProtocolVersionConfigMap(ownerReferences))) { configMap ⇒
          if (configMap.data.getOrElse(Operator.ProtocolVersionKey, "") != Operator.ProtocolVersion) {
            client.update(configMap.copy(data = Map(Operator.ProtocolVersionKey -> Operator.ProtocolVersion)))
          } else {
            Future.successful(configMap)
          }
        }
      },
      protocolVersionTimeout
    )
  }

  private def getGCInfo: List[(String, javax.management.ObjectName)] = {
    val gcMxBeans = ManagementFactory.getGarbageCollectorMXBeans()
    gcMxBeans.asScala.map(b ⇒ (b.getName, b.getObjectName)).toList
  }

  private def box(str: String): String =
    if ((str == null) || (str.isEmpty)) ""
    else {
      val line = s"""+${"-" * 80}+"""
      s"$line\n$str\n$line"
    }

  private def formatBuildInfo: String = {
    import BuildInfo._

    s"""
    |Name          : $name
    |Version       : $version
    |Scala Version : $scalaVersion
    |sbt Version   : $sbtVersion
    |Build Time    : $buildTime
    |Build User    : $buildUser
    """.stripMargin
  }

  private def formatDeploymentInfo(settings: Settings): String =
    s"""
    |Release version : ${settings.releaseVersion}
    """.stripMargin

  private def prettyPrintConfig(c: Config): String =
    c.root
      .render(
        ConfigRenderOptions
          .concise()
          .setFormatted(true)
          .setJson(false)
      )

  private def getJVMRuntimeParameters: String = {
    val runtime = Runtime.getRuntime
    import runtime._

    s"""
     |Available processors    : $availableProcessors
     |Free Memory in the JVM  : $freeMemory
     |Max Memory JVM can use  : $maxMemory
     |Total Memory in the JVM : $maxMemory
    """.stripMargin
  }
}

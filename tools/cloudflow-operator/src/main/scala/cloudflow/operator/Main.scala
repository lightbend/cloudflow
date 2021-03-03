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

import java.lang.management.ManagementFactory
import akka.actor._
import akka.datap.crd.App

import scala.concurrent.Await
import scala.jdk.CollectionConverters._
import scala.concurrent._
import scala.concurrent.duration._
import cloudflow.operator.action._
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import io.fabric8.kubernetes.api.model.{ ConfigMapBuilder, OwnerReference }
import io.fabric8.kubernetes.api.model.apiextensions.v1.{
  CustomResourceDefinitionBuilder,
  CustomResourceDefinitionSpecBuilder
}
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinitionSpec
import io.fabric8.kubernetes.client.utils.Serialization
import io.fabric8.kubernetes.client.{ Config, DefaultKubernetesClient, KubernetesClient }

object Main extends {

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem()

    try {
      implicit val ec = system.dispatcher
      val settings = Settings(system)
      implicit val ctx = settings.deploymentContext

      logStartOperatorMessage(settings)

      HealthChecks.serve(settings)

      // TODO: share with the CLI!
      // This should run before any fabric8 command
      Serialization.jsonMapper().registerModule(DefaultScalaModule)

      val client = connectToKubernetes()
      checkCRD(settings, client)

      val ownerReferences = getDeploymentOwnerReferences(settings, client)
      installProtocolVersion(settings, client, ownerReferences)

      import cloudflow.operator.action.runner._
      val runners = Map(AkkaRunner.Runtime -> new AkkaRunner(ctx.akkaRunnerDefaults))
      // TODO: re-enable this
      //        SparkRunner.Runtime -> new SparkRunner(ctx.sparkRunnerDefaults),
      //        FlinkRunner.Runtime -> new FlinkRunner(ctx.flinkRunnerDefaults))
      Operator.handleAppEvents(client, runners, ctx.podName, ctx.podNamespace)
      Operator.handleConfigurationUpdates(client, runners, ctx.podName)
      Operator.handleStatusUpdates(client, runners)
    } catch {
      case t: Throwable =>
        system.log.error(t, "Unexpected error starting cloudflow operator, terminating.")
        system.registerOnTermination(exitWithFailure())
        system.terminate()
    }
  }

  private def logStartOperatorMessage(settings: Settings)(implicit system: ActorSystem) =
    system.log.info(s"""
      |Started cloudflow operator ..
      |\n${box("Build Info")}
      |${formatBuildInfo}
      |\n${box("JVM Resources")}
      |${getJVMRuntimeParameters}
      |\n${box("GC Type")}
      |\n${getGCInfo}
      |\n${box("Cloudflow Context")}
      |${settings.deploymentContext.infoMessage}
      |\n${box("Deployment")}
      |${formatDeploymentInfo(settings)}
      """.stripMargin)

  private def getDeploymentOwnerReferences(settings: Settings, client: KubernetesClient): List[OwnerReference] = {
    client
      .apps()
      .deployments()
      .inNamespace(settings.podNamespace)
      .withName(Name.ofCloudflowOperatorDeployment)
      .get()
      .getMetadata()
      .getOwnerReferences()
      .asScala
      .toList
  }

  private def connectToKubernetes()(implicit system: ActorSystem): KubernetesClient = {
    val conf = Config.autoConfigure(null)
    val client = new DefaultKubernetesClient(conf).inAnyNamespace()
    system.log.info(s"Connected to Kubernetes cluster: ${conf.getCurrentContext.getContext.getCluster}")
    client
  }

  private def exitWithFailure() = System.exit(-1)

  private def checkCRD(settings: Settings, client: KubernetesClient)(implicit system: ActorSystem): Unit = {
    // TODO: should this go to helm charts or not
    Option(
      client
        .apiextensions()
        .v1beta1()
        .customResourceDefinitions()
        .withName(App.ResourceName)
        .get()) match {
      case Some(crd) if crd.getSpec.getVersion == App.GroupVersion =>
        system.log.info(s"CRD found at version ${App.GroupVersion}")
      case _ =>
        client
          .apiextensions()
          .v1beta1()
          .customResourceDefinitions()
          .inNamespace(settings.podNamespace)
          .withName(App.ResourceName)
          .create(App.Crd)
    }
  }

  private def installProtocolVersion(
      settings: Settings,
      client: KubernetesClient,
      ownerReferences: List[OwnerReference]): Unit = {
    client
      .configMaps()
      .inNamespace(settings.podNamespace)
      .withName(Operator.ProtocolVersionConfigMapName)
      .createOrReplace(Operator.ProtocolVersionConfigMap(ownerReferences))
  }

  private def getGCInfo: List[(String, javax.management.ObjectName)] = {
    val gcMxBeans = ManagementFactory.getGarbageCollectorMXBeans()
    gcMxBeans.asScala.map(b => (b.getName, b.getObjectName)).toList
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

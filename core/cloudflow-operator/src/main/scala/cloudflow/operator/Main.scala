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
import akka.datap.crd.App
import cloudflow.operator.action._
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.client.utils.Serialization
import io.fabric8.kubernetes.client.{ Config, DefaultKubernetesClient, KubernetesClient }

import java.lang.management.ManagementFactory
import scala.jdk.CollectionConverters._
import scala.util.{ Success, Try }

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

      // TODO: Needed for Spark?
      Serialization.jsonMapper().setSerializationInclusion(Include.NON_ABSENT)

      val client = connectToKubernetes(settings)

      // this registers deserializer
      client.customResources(App.customResourceDefinitionContext, classOf[App.Cr], classOf[App.List])

      checkCRD(settings, client)

      val ownerReferences = getDeploymentOwnerReferences(settings, client)
      installProtocolVersion(settings, client, ownerReferences)

      import cloudflow.operator.action.runner._

      val runners = Map(AkkaRunner.Runtime -> new AkkaRunner(ctx.akkaRunnerDefaults))

      Operator.handleEvents(client, runners, ctx.podName, ctx.podNamespace)
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
    Option(
      client
        .apps()
        .deployments()
        .inNamespace(settings.podNamespace)
        .withName(Name.ofCloudflowOperatorDeployment)
        .get())
      .map {
        _.getMetadata()
          .getOwnerReferences()
          .asScala
          .toList
      }
      .getOrElse(List())
  }

  private def connectToKubernetes(settings: Settings)(implicit system: ActorSystem): KubernetesClient = {
    val conf = Config.autoConfigure(null)
    val client = {
      settings.controlledNamespace match {
        case Some(ns) =>
          system.log.info(s"Connecting to namespace $ns")
          new DefaultKubernetesClient(conf).inNamespace(ns)
        case _ =>
          system.log.info(s"Connecting to all namespaces")
          new DefaultKubernetesClient(conf).inAnyNamespace()
      }
    }
    val cluster = Try { s": ${conf.getCurrentContext.getContext.getCluster}" }.getOrElse("")
    system.log.info(s"Connected to Kubernetes cluster $cluster")
    client
  }

  private def exitWithFailure() = System.exit(-1)

  private def checkCRD(settings: Settings, client: KubernetesClient)(implicit system: ActorSystem): Unit = {
    // TODO: should this go to helm charts or not
    Option(
      client
        .apiextensions()
        .v1()
        .customResourceDefinitions()
        .withName(App.ResourceName)
        .get()) match {
      case Some(crd) if crd.getSpec.getVersions().asScala.exists(_.getName == App.GroupVersion) =>
        system.log.info(s"CRD found at version ${App.GroupVersion}")
      case _ =>
        system.log.error(
          s"Cloudflow CRD not found, please install it: 'kubectl apply -f https://raw.githubusercontent.com/lightbend/cloudflow/v${BuildInfo.version}/core/cloudflow-crd/kubernetes/cloudflow-crd.yaml'")
        throw new Exception("Cloudflow CRD not found")
    }
  }

  private def installProtocolVersion(
      settings: Settings,
      client: KubernetesClient,
      ownerReferences: List[OwnerReference]): Unit = {
    client
      .secrets()
      .inNamespace(settings.podNamespace)
      .withName(App.CloudflowProtocolVersion)
      .createOrReplace(Operator.ProtocolVersionSecret(ownerReferences))
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

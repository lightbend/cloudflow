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

package cloudflow.operator.action.runner

import akka.cloudflow.config.{ CloudflowConfig, UnsafeCloudflowConfigLoader }
import akka.datap.crd.App
import akka.kube.actions.Action
import cloudflow.blueprint.VolumeMountDescriptor
import cloudflow.blueprint.deployment._
import cloudflow.operator.action._
import cloudflow.operator.event.ConfigInput
import com.typesafe.config._
import io.fabric8.kubernetes.api.model.rbac._
import io.fabric8.kubernetes.api.model.{ Config => _, _ }
import org.slf4j._

import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag
import scala.util._

object Runner {
  val SecretMountPath = "/etc/cloudflow-runner-secret"
  val DownwardApiVolume =
    new VolumeBuilder()
      .withName("downward-api-volume")
      .withNewDownwardAPI()
      .withItems(
        new DownwardAPIVolumeFileBuilder()
          .withFieldRef(new ObjectFieldSelectorBuilder().withFieldPath("metadata.uid").build())
          .withPath("metadata.uid")
          .build(),
        new DownwardAPIVolumeFileBuilder()
          .withFieldRef(new ObjectFieldSelectorBuilder().withFieldPath("metadata.name").build())
          .withPath("metadata.name")
          .build(),
        new DownwardAPIVolumeFileBuilder()
          .withFieldRef(new ObjectFieldSelectorBuilder().withFieldPath("metadata.namespace").build())
          .withPath("metadata.namespace")
          .build())
      .endDownwardAPI()
      .build()

  val DownwardApiVolumeMount = {
    new VolumeMountBuilder()
      .withName(DownwardApiVolume.getName)
      .withMountPath("/mnt/downward-api-volume/")
      .build()
  }

  val DockerContainerGroupId = 185

  def toBlueprint(deployment: App.Deployment): StreamletDeployment = {
    StreamletDeployment(
      name = deployment.name,
      runtime = deployment.runtime,
      image = deployment.image,
      streamletName = deployment.streamletName,
      className = deployment.className,
      endpoint = deployment.endpoint.flatMap { endpoint =>
        for {
          appId <- endpoint.appId
          streamlet <- endpoint.streamlet
          containerPort <- endpoint.containerPort
        } yield {
          Endpoint(appId = appId, streamlet = streamlet, containerPort = containerPort)
        }
      },
      secretName = deployment.secretName,
      config = JsonConfig(deployment.config),
      portMappings = deployment.portMappings.map {
        case (k, v) =>
          k -> Topic(id = v.id, cluster = v.cluster, config = JsonConfig(v.config))
      },
      volumeMounts = {
        if (deployment.volumeMounts == null || deployment.volumeMounts.isEmpty) None
        else
          Some(deployment.volumeMounts.map { vmd =>
            VolumeMountDescriptor(
              name = vmd.name,
              path = vmd.path,
              accessMode = vmd.accessMode,
              pvcName = vmd.pvcName.getOrElse(""))
          }.toList)
      },
      replicas = deployment.replicas)
  }
}

/**
 * A Runner translates into a Runner Kubernetes resource, and a ConfigMap that configures the runner.
 */
trait Runner[T <: HasMetadata] {
  val log = LoggerFactory.getLogger(this.getClass)

  def runtime: String

  def actions(newApp: App.Cr, currentApp: Option[App.Cr], runners: Map[String, Runner[_]])(
      implicit ct: ClassTag[T]): Seq[Action] = {

    val newDeployments = newApp.getSpec.deployments.filter(_.runtime == runtime)

    val currentDeployments = currentApp.map(_.getSpec.deployments.filter(_.runtime == runtime)).getOrElse(Vector())
    val currentDeploymentNames = currentDeployments.map(_.name)
    val newDeploymentNames = newDeployments.map(_.name)

    // delete streamlet deployments by name that are in the current app but are not listed in the new app
    val deleteActions = currentDeployments
      .filterNot(deployment => newDeploymentNames.contains(deployment.name))
      .flatMap { deployment =>
        Seq(deleteResource(resourceName(deployment), newApp.namespace))
      }

    // create streamlet deployments by name that are not in the current app but are listed in the new app
    val createActions = newDeployments
      .filterNot(deployment => currentDeploymentNames.contains(deployment.name))
      .flatMap { deployment =>
        Seq(ActionExtension.providedSecretRetry(deployment.secretName, newApp.namespace)({
          case Some(secret) =>
            createOrReplaceResource(resource(deployment, newApp, secret))
          case None =>
            val msg =
              s"Deployment of ${newApp.getSpec.appId} is pending, secret ${deployment.secretName} is missing for streamlet deployment '${deployment.name}'."
            log.info(msg)
            CloudflowStatus.pendingAction(
              newApp,
              runners,
              s"Awaiting configuration secret ${deployment.secretName} for streamlet deployment '${deployment.name}'.")
        })(60))
      }

    // update streamlet deployments by name that are in both the current app and the new app
    val _updateActions = newDeployments
      .filter(deployment => currentDeploymentNames.contains(deployment.name))
      .flatMap { deployment =>
        updateActions(newApp, runners, deployment)
      }

    deleteActions ++ createActions ++ _updateActions
  }

  def prepareNamespaceActions(app: App.Cr, labels: CloudflowLabels, ownerReferences: List[OwnerReference]) =
    appActions(app, labels, ownerReferences)

  def appActions(app: App.Cr, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): Seq[Action]

  def updateActions(newApp: App.Cr, runners: Map[String, Runner[_]], deployment: App.Deployment)(
      implicit ct: ClassTag[T]): Seq[Action] = {
    Seq(Action.get[Secret](deployment.secretName, newApp.namespace) { secret: Option[Secret] =>
      secret match {
        case Some(sec) =>
          createOrReplaceResource(resource(deployment, newApp, sec))
        case None =>
          val msg = s"Secret ${deployment.secretName} is missing for streamlet deployment '${deployment.name}'."
          log.error(msg)
          CloudflowStatus.errorAction(newApp, runners, msg)
      }
    })
  }

  def streamletChangeAction(
      app: App.Cr,
      runners: Map[String, Runner[_]],
      streamletDeployment: App.Deployment,
      secret: Secret): Action

  def defaultReplicas: Int
  def expectedPodCount(deployment: App.Deployment): Int

  val createEventPolicyRule =
    new PolicyRuleBuilder()
      .withApiGroups("")
      .withResources("events")
      .withVerbs("get", "create", "update")
      .build()

  final val RuntimeMainClass = "cloudflow.runner.Runner"
  final val RunnerJarName = "cloudflow-runner.jar"
  final val JavaOptsEnvVarName = "JAVA_OPTS"

  final val PrometheusAgentKey = "prometheus"

  def resourceName(deployment: App.Deployment): String

  /**
   * Creates the runner resource.
   */
  def resource(
      deployment: App.Deployment,
      app: App.Cr,
      configSecret: Secret,
      updateLabels: Map[String, String] = Map()): T

  def createOrReplaceResource(res: T)(implicit ct: ClassTag[T]): Action

  def deleteResource(name: String, namespace: String)(implicit ct: ClassTag[T]): Action

  def getPodsConfig(secret: Secret): PodsConfig = {
    val str = getData(secret, ConfigInput.PodsConfigDataKey)

    (for {
      configStr <- Try { ConfigFactory.parseString(str) }
      config <- UnsafeCloudflowConfigLoader.loadPodConfig(configStr)
      podConfig <- PodsConfig.fromKubernetes(config)
    } yield {
      podConfig
    }).recover {
        case e =>
          log.error(
            s"Detected pod configs in secret '${secret.getMetadata().getName()}' that contains invalid configuration data, IGNORING configuration.",
            e)
          PodsConfig()
      }
      .getOrElse(PodsConfig())
  }

  def getRuntimeConfig(secret: Secret): Config = {
    val str = getData(secret, ConfigInput.RuntimeConfigDataKey)
    Try(ConfigFactory.parseString(str))
      .recover {
        case e =>
          log.error(
            s"Detected runtime config in secret '${secret.getMetadata().getName()}' that contains invalid configuration data, IGNORING configuration.",
            e)
          ConfigFactory.empty
      }
      .getOrElse(ConfigFactory.empty)
  }

  private def getData(secret: Secret, key: String): String = {
    Base64Helper.decode(Option(secret.getData).map(_.getOrDefault(key, "")).getOrElse(""))
  }

  def getEnvironmentVariables(podsConfig: PodsConfig, podName: String): Option[List[EnvVar]] =
    podsConfig.pods
      .get(podName)
      .orElse(podsConfig.pods.get(PodsConfig.CloudflowPodName))
      .flatMap { podConfig =>
        podConfig.containers.get(PodsConfig.CloudflowContainerName).map { containerConfig =>
          // excluding JAVA_OPTS from env vars and passing it through via javaOptions.
          containerConfig.env.filterNot(_.getName == JavaOptsEnvVarName)
        }
      }

  def getVolumeMounts(podsConfig: PodsConfig, podName: String): List[VolumeMount] = {
    podsConfig.pods
      .get(podName)
      .orElse(podsConfig.pods.get(PodsConfig.CloudflowPodName))
      .flatMap { podConfig =>
        podConfig.containers.get(PodsConfig.CloudflowContainerName).map { containerConfig =>
          containerConfig.volumeMounts
        }
      }
      .getOrElse(List())
  }

  def getContainerPorts(podsConfig: PodsConfig, podName: String): List[ContainerPort] = {
    podsConfig.pods
      .get(podName)
      .orElse(podsConfig.pods.get(PodsConfig.CloudflowPodName))
      .flatMap { podConfig =>
        podConfig.containers.get(PodsConfig.CloudflowContainerName).map { containerConfig =>
          containerConfig.ports
        }
      }
      .getOrElse(List())
  }

  def getJavaOptions(podsConfig: PodsConfig, podName: String): Option[String] =
    podsConfig.pods
      .get(podName)
      .orElse(podsConfig.pods.get(PodsConfig.CloudflowPodName))
      .flatMap { podConfig =>
        podConfig.containers.get(PodsConfig.CloudflowContainerName).flatMap { containerConfig =>
          containerConfig.env.find(_.getName == JavaOptsEnvVarName).map(_.getValue)
        }
      }

  def getLabels(podsConfig: PodsConfig, podName: String): Map[String, String] =
    podsConfig.pods
      .get(podName)
      .orElse(podsConfig.pods.get(PodsConfig.CloudflowPodName))
      .map { podConfig =>
        podConfig.labels
      }
      .getOrElse(Map())

  def getAnnotations(podsConfig: PodsConfig, podName: String): Map[String, String] =
    podsConfig.pods
      .get(podName)
      .orElse(podsConfig.pods.get(PodsConfig.CloudflowPodName))
      .map { podConfig =>
        podConfig.annotations
      }
      .getOrElse(Map())

  def getVolumes(podsConfig: PodsConfig, podName: String): List[Volume] =
    podsConfig.pods
      .get(podName)
      .orElse(podsConfig.pods.get(PodsConfig.CloudflowPodName))
      .map { podConfig =>
        podConfig.volumes
      }
      .getOrElse(List())

}

object PodsConfig {

  val logger = LoggerFactory.getLogger(this.getClass)

  val CloudflowPodName = "pod"
  val CloudflowContainerName = "container"

  /*
   *  The expected format is:
   *  {{{
   *  kubernetes {
   *    pods {
   *      # pod is special name for cloudflow
   *      <pod-name> | pod {
   *        containers {
   *          # container is special name for cloudflow
   *          <container-name> | container {
   *            env = [
   *             {name = "env-key", value = "env-value"}
   *            ]
   *            resources {
   *              limits {
   *                memory = "200Mi"
   *              }
   *              requests {
   *                memory = "1200Mi"
   *              }
   *            }
   *          }
   *        }
   *      }
   *    }
   *  }
   *  }}}
   */

  private def getContainerConfig(container: CloudflowConfig.Container): ContainerConfig = {
    val env = container.env
      .map {
        _.map { env =>
          new EnvVarBuilder()
            .withName(env.name)
            .withValue(env.value)
            .build()
        }
      }
      .getOrElse(List())

    val resources = {
      val limits = {
        container.resources.limits.cpu.map(v => "cpu" -> Quantity.parse(v.value)).toMap ++
        container.resources.limits.memory.map(v => "memory" -> Quantity.parse(v.value)).toMap
      }

      val requests = {
        container.resources.requests.cpu.map(v => "cpu" -> Quantity.parse(v.value)).toMap ++
        container.resources.requests.memory.map(v => "memory" -> Quantity.parse(v.value)).toMap
      }

      if ((limits ++ requests).isEmpty) {
        None
      } else {
        Some(
          new ResourceRequirementsBuilder()
            .withLimits(limits.asJava)
            .withRequests(requests.asJava)
            .build())
      }
    }

    val volumeMounts = container.volumeMounts.map {
      case (name, vm) =>
        new VolumeMountBuilder()
          .withName(name)
          .withMountPath(vm.mountPath)
          .withSubPath(vm.subPath)
          .withReadOnly(vm.readOnly)
          .build()
    }.toList

    val ports = container.ports
      .map {
        _.map { port =>
          val base = new ContainerPortBuilder()
            .withName(port.name.map(_.value).getOrElse(null)) // TODO: check if empty string or null
            .withContainerPort(port.containerPort)
            .withHostIP(port.hostIP)
            .withProtocol(port.protocol)

          (port.hostPort match {
            case Some(hp) => base.withHostPort(hp)
            case _        => base
          }).build()
        }
      }
      .getOrElse(List())

    ContainerConfig(env = env, resources = resources, volumeMounts = volumeMounts, ports = ports)
  }

  private def getPodConfig(pod: CloudflowConfig.Pod): PodConfig = {
    val containers = pod.containers.map { case (k, v)   => k -> getContainerConfig(v) }
    val labels = pod.labels.map { case (k, v)           => k.key -> v.value }
    val annotations = pod.annotations.map { case (k, v) => k.key -> v.value }
    val volumes: List[Volume] = pod.volumes.map {
      case (name, volume) =>
        volume match {
          case secret: CloudflowConfig.SecretVolume =>
            new VolumeBuilder()
              .withName(name)
              .withSecret(
                new SecretVolumeSourceBuilder()
                  .withNewSecretName(secret.name)
                  .build())
              .build()
          case pvc: CloudflowConfig.PvcVolume =>
            new VolumeBuilder()
              .withName(name)
              .withPersistentVolumeClaim(
                new PersistentVolumeClaimVolumeSourceBuilder()
                  .withClaimName(pvc.name)
                  .withReadOnly(pvc.readOnly)
                  .build())
              .build()
          case cm: CloudflowConfig.ConfigMapVolume =>
            new VolumeBuilder()
              .withName(name)
              .withConfigMap(
                new ConfigMapVolumeSourceBuilder()
                  .withName(cm.name)
                  .withOptional(cm.optional)
                  .withItems(cm.items
                    .map {
                      case (k, item) => new KeyToPathBuilder().withKey(k).withPath(item.path).build()
                    }
                    .toList
                    .asJava)
                  .build())
              .build()
          case unknown =>
            logger.error(s"Found unknown $unknown volume type skipping")
            throw new Exception(s"Unknown volume $unknown")
        }
    }.toList

    PodConfig(containers = containers, labels = labels, annotations = annotations, volumes = volumes)
  }

  def fromKubernetes(kubernetes: CloudflowConfig.Kubernetes): Try[PodsConfig] = {
    Try {
      PodsConfig(kubernetes.pods.map { case (k, v) => k -> getPodConfig(v) })
    }
  }
}

final case class PodsConfig(pods: Map[String, PodConfig] = Map()) {
  def isEmpty = pods.isEmpty
  def nonEmpty = pods.nonEmpty
  def size = pods.size
}

final case class PodConfig(
    containers: Map[String, ContainerConfig],
    labels: Map[String, String] = Map(),
    annotations: Map[String, String] = Map(),
    volumes: List[Volume] = List())

final case class ContainerConfig(
    env: List[EnvVar] = List(),
    resources: Option[ResourceRequirements] = None,
    volumeMounts: List[VolumeMount] = List(),
    ports: List[ContainerPort] = List())

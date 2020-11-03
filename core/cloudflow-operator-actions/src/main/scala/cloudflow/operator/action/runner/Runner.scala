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

package cloudflow.operator.action.runner

import java.nio.charset.StandardCharsets

import scala.collection.JavaConverters._
import scala.util._
import com.typesafe.config._
import play.api.libs.json._
import org.slf4j._
import skuber.json.rbac.format._
import skuber.json.format._
import skuber.rbac._
import skuber._
import cloudflow.blueprint.deployment._

import cloudflow.operator._
import cloudflow.operator.event.ConfigInputChangeEvent
import cloudflow.operator.action._

object Runner {
  val ConfigMapMountPath = "/etc/cloudflow-runner"
  val SecretMountPath    = "/etc/cloudflow-runner-secret"
  val DownwardApiVolume = Volume(
    name = "downward-api-volume",
    source = Volume.DownwardApiVolumeSource(items = List(
      Volume.DownwardApiVolumeFile(
        fieldRef = Volume.ObjectFieldSelector(fieldPath = "metadata.uid"),
        path = "metadata.uid",
        resourceFieldRef = None
      ),
      Volume.DownwardApiVolumeFile(
        fieldRef = Volume.ObjectFieldSelector(fieldPath = "metadata.name"),
        path = "metadata.name",
        resourceFieldRef = None
      ),
      Volume.DownwardApiVolumeFile(
        fieldRef = Volume.ObjectFieldSelector(fieldPath = "metadata.namespace"),
        path = "metadata.namespace",
        resourceFieldRef = None
      )
    )
    )
  )
  val DownwardApiVolumeMount = Volume.Mount(DownwardApiVolume.name, "/mnt/downward-api-volume/")

  val DockerContainerGroupId = 185
}

/**
 * A Runner translates into a Runner Kubernetes resource, and a ConfigMap that configures the runner.
 */
trait Runner[T <: ObjectResource] {
  val log = LoggerFactory.getLogger(this.getClass)
  // The format for the runner resource T
  def format: Format[T]
  // The editor for the runner resource T to modify the metadata for update
  def editor: ObjectEditor[T]
  // The editor for the configmap to modify the metadata for update
  def configEditor: ObjectEditor[ConfigMap]
  // The resource definition for the runner resource T
  def resourceDefinition: ResourceDefinition[T]

  def runtime: String

  def actions(
      newApp: CloudflowApplication.CR,
      currentApp: Option[CloudflowApplication.CR],
      namespace: String
  ): Seq[ResourceAction[ObjectResource]] = {
    implicit val ft = format
    implicit val rd = resourceDefinition

    val newDeployments = newApp.spec.deployments.filter(_.runtime == runtime)

    val currentDeployments     = currentApp.map(_.spec.deployments.filter(_.runtime == runtime)).getOrElse(Vector())
    val currentDeploymentNames = currentDeployments.map(_.name)
    val newDeploymentNames     = newDeployments.map(_.name)

    // delete streamlet deployments by name that are in the current app but are not listed in the new app
    val deleteActions = currentDeployments
      .filterNot(deployment ⇒ newDeploymentNames.contains(deployment.name))
      .flatMap { deployment ⇒
        Seq(
          Action.delete[T](resourceName(deployment), namespace),
          Action.delete[T](configResourceName(deployment), namespace)
        )
      }

    // create streamlet deployments by name that are not in the current app but are listed in the new app
    val createActions = newDeployments
      .filterNot(deployment ⇒ currentDeploymentNames.contains(deployment.name))
      .flatMap { deployment ⇒
        Seq(
          Action.createOrUpdate(configResource(deployment, newApp, namespace), configEditor),
          Action.provided[Secret, ObjectResource](
            deployment.secretName,
            namespace, {
              case Some(secret) => Action.createOrUpdate(resource(deployment, newApp, secret, namespace), editor)
              case None =>
                val msg = s"Secret ${deployment.secretName} is missing for streamlet deployment '${deployment.name}'."
                log.error(msg)
                CloudflowApplication.Status.errorAction(newApp, msg)
            }
          )
        )
      }

    // update streamlet deployments by name that are in both the current app and the new app
    val _updateActions = newDeployments
      .filter(deployment ⇒ currentDeploymentNames.contains(deployment.name))
      .flatMap { deployment ⇒
        updateActions(newApp, namespace, deployment)
      }
      .toSeq

    deleteActions ++ createActions ++ _updateActions
  }

  def prepareNamespaceActions(app: CloudflowApplication.CR,
                              namespace: String,
                              labels: CloudflowLabels,
                              ownerReferences: List[OwnerReference]) =
    appActions(app, namespace, labels, ownerReferences) ++
        serviceAccountAction(namespace, labels, ownerReferences)

  def appActions(app: CloudflowApplication.CR,
                 namespace: String,
                 labels: CloudflowLabels,
                 ownerReferences: List[OwnerReference]): Seq[Action]

  def updateActions(newApp: CloudflowApplication.CR,
                    namespace: String,
                    deployment: StreamletDeployment): Seq[ResourceAction[ObjectResource]] = {
    implicit val f  = format
    implicit val rd = resourceDefinition
    Seq(
      Action.createOrUpdate(configResource(deployment, newApp, namespace), configEditor),
      Action.provided[Secret, ObjectResource](
        deployment.secretName,
        namespace, {
          case Some(secret) =>
            Action.createOrUpdate(resource(deployment, newApp, secret, namespace), editor)
          case None =>
            val msg = s"Secret ${deployment.secretName} is missing for streamlet deployment '${deployment.name}'."
            log.error(msg)
            CloudflowApplication.Status.errorAction(newApp, msg)
        }
      )
    )
  }

  def streamletChangeAction(app: CloudflowApplication.CR, streamletDeployment: StreamletDeployment): ResourceAction[ObjectResource]

  def serviceAccountAction(namespace: String, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): Seq[Action] =
    Vector(Action.createOrUpdate(roleBinding(namespace, labels, ownerReferences), roleBindingEditor))

  def defaultReplicas: Int

  def roleEditor: ObjectEditor[Role]               = (obj: Role, newMetadata: ObjectMeta) ⇒ obj.copy(metadata = newMetadata)
  def roleBindingEditor: ObjectEditor[RoleBinding] = (obj: RoleBinding, newMetadata: ObjectMeta) ⇒ obj.copy(metadata = newMetadata)

  def roleBinding(namespace: String, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): RoleBinding =
    RoleBinding(
      metadata = ObjectMeta(
        name = Name.ofRoleBinding(),
        namespace = namespace,
        labels = labels(Name.ofRoleBinding()),
        ownerReferences = ownerReferences
      ),
      kind = "RoleBinding",
      roleRef = RoleRef("rbac.authorization.k8s.io", "Role", BasicUserRole),
      subjects = List(
        Subject(
          None,
          "ServiceAccount",
          Name.ofServiceAccount,
          Some(namespace)
        )
      )
    )

  val createEventPolicyRule = PolicyRule(
    apiGroups = List(""),
    attributeRestrictions = None,
    nonResourceURLs = List(),
    resourceNames = List(),
    resources = List("events"),
    verbs = List("get", "create", "update")
  )
  val BasicUserRole = "system:basic-user"

  final val RuntimeMainClass   = "cloudflow.runner.Runner"
  final val RunnerJarName      = "cloudflow-runner.jar"
  final val JavaOptsEnvVarName = "JAVA_OPTS"

  def prometheusConfig: PrometheusConfig

  /**
   * Creates the configmap for the runner.
   */
  def configResource(
      deployment: StreamletDeployment,
      app: CloudflowApplication.CR,
      namespace: String
  ): ConfigMap = {
    val labels          = CloudflowLabels(app)
    val ownerReferences = List(OwnerReference(app.apiVersion, app.kind, app.metadata.name, app.metadata.uid, Some(true), Some(true)))

    val configData = Vector(
      RunnerConfig(app.spec.appId, app.spec.appVersion, deployment),
      prometheusConfig
    )
    val name = Name.ofConfigMap(deployment.name)
    ConfigMap(
      metadata = ObjectMeta(name = name, namespace = namespace, labels = labels(name), ownerReferences = ownerReferences),
      data = configData.map(cd ⇒ cd.filename -> cd.data).toMap
    )
  }
  def configResourceName(deployment: StreamletDeployment) = Name.ofConfigMap(deployment.name)
  def resourceName(deployment: StreamletDeployment): String

  /**
   * Creates the runner resource.
   */
  def resource(
      deployment: StreamletDeployment,
      app: CloudflowApplication.CR,
      configSecret: Secret,
      namespace: String,
      updateLabels: Map[String, String] = Map()
  ): T

  def getPodsConfig(secret: Secret): PodsConfig = {
    val str = getData(secret, ConfigInputChangeEvent.PodsConfigDataKey)
    PodsConfig
      .fromConfig(ConfigFactory.parseString(str))
      .recover {
        case e =>
          log.error(
            s"Detected pod configs in secret '${secret.metadata.name}' that contains invalid configuration data, IGNORING configuration.",
            e
          )
          PodsConfig()
      }
      .getOrElse(PodsConfig())
  }

  def getRuntimeConfig(secret: Secret): Config = {
    val str = getData(secret, ConfigInputChangeEvent.RuntimeConfigDataKey)
    Try(ConfigFactory.parseString(str))
      .recover {
        case e =>
          log.error(
            s"Detected runtime config in secret '${secret.metadata.name}' that contains invalid configuration data, IGNORING configuration.",
            e
          )
          ConfigFactory.empty
      }
      .getOrElse(ConfigFactory.empty)
  }

  private def getData(secret: Secret, key: String): String =
    secret.data.get(key).map(bytes => new String(bytes, StandardCharsets.UTF_8)).getOrElse("")

  def getEnvironmentVariables(podsConfig: PodsConfig, podName: String): Option[List[EnvVar]] =
    podsConfig.pods
      .get(podName)
      .orElse(podsConfig.pods.get(PodsConfig.CloudflowPodName))
      .flatMap { podConfig =>
        podConfig.containers.get(PodsConfig.CloudflowContainerName).map { containerConfig =>
          // excluding JAVA_OPTS from env vars and passing it through via javaOptions.
          containerConfig.env.filterNot(_.name == JavaOptsEnvVarName)
        }
      }

  def getVolumeMounts(podsConfig: PodsConfig, podName: String): List[Volume.Mount] =
    podsConfig.pods
      .get(podName)
      .orElse(podsConfig.pods.get(PodsConfig.CloudflowPodName))
      .flatMap { podConfig =>
        podConfig.containers.get(PodsConfig.CloudflowContainerName).map { containerConfig =>
          containerConfig.volumeMounts
        }
      }
      .getOrElse(List())

  def getJavaOptions(podsConfig: PodsConfig, podName: String): Option[String] =
    podsConfig.pods
      .get(podName)
      .orElse(podsConfig.pods.get(PodsConfig.CloudflowPodName))
      .flatMap { podConfig =>
        podConfig.containers.get(PodsConfig.CloudflowContainerName).flatMap { containerConfig =>
          containerConfig.env.find(_.name == JavaOptsEnvVarName).map(_.value).collect { case EnvVar.StringValue(str) => str }
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

  def getVolumes(podsConfig: PodsConfig, podName: String): List[Volume] =
    podsConfig.pods
      .get(podName)
      .orElse(podsConfig.pods.get(PodsConfig.CloudflowPodName))
      .map { podConfig =>
        podConfig.volumes
      }
      .getOrElse(List())

}

import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._

import collection.JavaConverters._
import skuber.Resource.Quantity

object PodsConfig {

  val logger = LoggerFactory.getLogger(this.getClass)

  val CloudflowPodName       = "pod"
  val CloudflowContainerName = "container"

  implicit val quantityReader: ValueReader[Quantity] = new ValueReader[Quantity] {
    def read(config: Config, path: String) = {
      // skuber quantity does not break construction
      val q = Quantity(config.getString(path))
      Try {
        q.amount
        q
      }.recoverWith {
          case e => Failure(new Exception(s"invalid quantity at key '$path'", e))
        }
        // users might use hocon MB, GB, gb, values, so fallback to that.
        .orElse(Try(Quantity(config.getMemorySize(path).toBytes.toString)))
        .get
    }
  }

  implicit val envVarValueReader: ValueReader[EnvVar.Value] = new ValueReader[EnvVar.Value] {
    def read(config: Config, path: String) =
      config.as[Option[String]](path).map(EnvVar.StringValue).getOrElse(EnvVar.StringValue(""))
  }

  implicit val envVarReader: ValueReader[EnvVar] = ValueReader.relative { envConfig =>
    val name  = envConfig.getString("name")
    val value = envConfig.as[EnvVar.Value]("value")
    EnvVar(name, value)
  }

  implicit val ContainerConfigReader: ValueReader[ContainerConfig] = ValueReader.relative { containerConfig =>
    val env          = containerConfig.as[Option[List[EnvVar]]]("env")
    val resources    = containerConfig.as[Option[Resource.Requirements]]("resources")
    val volumeMounts = containerConfig.as[Option[List[Volume.Mount]]]("volume-mounts")
    ContainerConfig(env.getOrElse(List()), resources, volumeMounts.getOrElse(List()))
  }

  implicit val containerConfMapReader: ValueReader[Map[String, PodConfig]] = ValueReader.relative { config ⇒
    asConfigObjectToMap[PodConfig](config)
  }

  implicit val volumeMountsListConfReader: ValueReader[List[Volume.Mount]] = ValueReader.relative { config =>
    asConfigObjectToMap[Volume.Mount](config).map {
      case (volumeName, valuesMount) =>
        valuesMount.copy(name = volumeName)
    }.toList
  }

  /**
   * Currently not providing MountPropagation
   */
  implicit val volumeMountsConfReader: ValueReader[Volume.Mount] = ValueReader.relative { config =>
    Volume.Mount(
      name = config.getOrElse[String]("name", ""),
      mountPath = config.getOrElse[String]("mount-path", ""),
      readOnly = config.getOrElse[Boolean]("read-only", false),
      subPath = config.getOrElse[String]("subPath", "")
    )
  }

  implicit val volumesConfReader: ValueReader[List[Volume]] = ValueReader.relative { config =>
    asConfigObjectToMap[Volume.Source](config).map {
      case (volumeName, source) => Volume(volumeName, source)
    }.toList
  }

  /**
   * TODO How to make this optional?
   */
  implicit val sourceConfReader: ValueReader[Volume.Source] = ValueReader.relative { config =>
    val res: Option[Volume.Source] = config.root().keySet().toArray().headOption.map {
      case "secret" =>
        config.as[Volume.Secret]("secret")
      case "pvc" =>
        config.as[Volume.PersistentVolumeClaimRef]("pvc")
      case x =>
        throw new IllegalArgumentException(
          s"volume definition '$x' in config: '$config' is not 'secret' nor 'pvc'. These are the only options"
        )
    }
    res.getOrElse(Volume.GenericVolumeSource(config.toString))
  }

  implicit val secretsConfReader: ValueReader[Volume.Secret] = ValueReader.relative { config =>
    Volume.Secret(secretName = config.as[String]("name"))
  }

  implicit val persistentVolumeClaimRefConfReader: ValueReader[Volume.PersistentVolumeClaimRef] = ValueReader.relative { config =>
    Volume.PersistentVolumeClaimRef(
      claimName = config.as[String]("name"),
      readOnly = config.as[Boolean]("read-only")
    )
  }

  implicit val podConfMapReader: ValueReader[PodConfig] = ValueReader.relative { config ⇒
    val labels = config
      .as[Option[Map[String, String]]]("labels")
      .getOrElse(Map.empty[String, String])
    val volumes = config
      .as[Option[List[Volume]]]("volumes")
      .getOrElse(List.empty[Volume])
    val containers = config
      .as[Option[Map[String, ContainerConfig]]]("containers")
      .getOrElse(Map.empty[String, ContainerConfig])
    PodConfig(containers, labels, volumes)
  }

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
  def fromConfig(config: Config): Try[PodsConfig] =
    if (config.isEmpty) Try(PodsConfig())
    else Try(PodsConfig(asConfigObjectToMap[PodConfig](config.getConfig("kubernetes.pods"))))

  def asConfigObjectToMap[T: ValueReader](config: Config): Map[String, T] =
    config.root.keySet.asScala.map(key ⇒ key → config.as[T](key)).toMap
}

final case class PodsConfig(pods: Map[String, PodConfig] = Map()) {
  def isEmpty  = pods.isEmpty
  def nonEmpty = pods.nonEmpty
  def size     = pods.size
}

final case class PodConfig(
    containers: Map[String, ContainerConfig],
    labels: Map[String, String] = Map(),
    volumes: List[Volume] = List()
)

final case class ContainerConfig(
    env: List[EnvVar] = List(),
    resources: Option[Resource.Requirements] = None,
    volumeMounts: List[Volume.Mount] = List()
)

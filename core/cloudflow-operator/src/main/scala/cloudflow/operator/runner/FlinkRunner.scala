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
package runner

import scala.collection.JavaConverters._
import scala.util.Try
import com.typesafe.config._
import play.api.libs.json._
import skuber._
import skuber.json.format._
import skuber.Resource._
import cloudflow.blueprint.deployment._
import FlinkResource._
import skuber.ResourceSpecification.Subresources

/**
 * Creates the ConfigMap and the Runner resource (a FlinkResource.CR) that define a Flink [[Runner]].
 */
object FlinkRunner extends Runner[CR] {
  def format = implicitly[Format[CR]]
  def editor = new ObjectEditor[CR] {
    override def updateMetadata(obj: CR, newMetadata: ObjectMeta) = obj.copy(metadata = newMetadata)
  }
  def configEditor = new ObjectEditor[ConfigMap] {
    override def updateMetadata(obj: ConfigMap, newMetadata: ObjectMeta) = obj.copy(metadata = newMetadata)
  }

  def resourceDefinition    = implicitly[ResourceDefinition[CR]]
  final val runtime         = "flink"
  final val PVCMountPath    = "/mnt/flink/storage"
  final val DefaultReplicas = 2

  final val JobManagerPod  = "job-manager"
  final val TaskManagerPod = "task-manager"

  def resource(
      deployment: StreamletDeployment,
      app: CloudflowApplication.CR,
      configSecret: Secret,
      namespace: String,
      updateLabels: Map[String, String] = Map()
  )(implicit ctx: DeploymentContext): CR = {
    val podsConfig = getPodsConfig(configSecret)

    val javaOptions = getJavaOptions(podsConfig, PodsConfig.CloudflowPodName)

    val image             = deployment.image
    val streamletToDeploy = app.spec.streamlets.find(streamlet ⇒ streamlet.name == deployment.streamletName)

    val volumes      = makeVolumesSpec(deployment, app, streamletToDeploy)
    val volumeMounts = makeVolumeMountsSpec(streamletToDeploy)

    import ctx.flinkRunnerSettings._

    val jobManagerConfig = JobManagerConfig(
      Some(jobManagerSettings.replicas),
      getJobManagerResourceRequirements(podsConfig, JobManagerPod),
      Some(EnvConfig(getEnvironmentVariables(podsConfig, JobManagerPod)))
    )

    val scale = deployment.replicas

    val taskManagerConfig = TaskManagerConfig(
      Some(taskManagerSettings.taskSlots),
      getTaskManagerResourceRequirements(podsConfig, TaskManagerPod),
      Some(EnvConfig(getEnvironmentVariables(podsConfig, TaskManagerPod)))
    )

    val flinkConfig: Map[String, String] = Map(
        "state.backend"                    -> "filesystem",
        "state.backend.fs.checkpointdir"   -> s"file://${PVCMountPath}/checkpoints/${deployment.streamletName}",
        "state.checkpoints.dir"            -> s"file://${PVCMountPath}/externalized-checkpoints/${deployment.streamletName}",
        "state.savepoints.dir"             -> s"file://${PVCMountPath}/savepoints/${deployment.streamletName}"
      ) ++ javaOptions.map("env.java.opts" -> _) ++ getFlinkConfig(configSecret)

    val _spec = Spec(
      image = image,
      jarName = RunnerJarName,
      parallelism = scale.map(_ * taskManagerSettings.taskSlots).getOrElse(ctx.flinkRunnerSettings.parallelism),
      entryClass = RuntimeMainClass,
      volumes = volumes,
      volumeMounts = volumeMounts,
      flinkConfig = flinkConfig,
      jobManagerConfig = jobManagerConfig,
      taskManagerConfig = taskManagerConfig
    )

    val name      = resourceName(deployment)
    val appLabels = CloudflowLabels(app)
    val labels = appLabels.withComponent(name, CloudflowLabels.StreamletComponent) ++ updateLabels ++
          Map(Operator.StreamletNameLabel -> deployment.streamletName, Operator.AppIdLabel -> app.spec.appId).mapValues(Name.ofLabelValue)
    val ownerReferences = List(OwnerReference(app.apiVersion, app.kind, app.metadata.name, app.metadata.uid, Some(true), Some(true)))

    CustomResource[Spec, Status](_spec)
      .withMetadata(
        ObjectMeta(
          name = name,
          namespace = namespace,
          annotations = Map("prometheus.io/scrape" -> "true", "prometheus.io/port" -> PrometheusConfig.PrometheusJmxExporterPort.toString),
          labels = labels,
          ownerReferences = ownerReferences
        )
      )
  }

  def resourceName(deployment: StreamletDeployment): String = Name.ofFlinkApplication(deployment.name)

  private def getJobManagerResourceRequirements(podsConfig: PodsConfig,
                                                podName: String)(implicit ctx: DeploymentContext): Option[Requirements] = {
    import ctx.flinkRunnerSettings._

    var resourceRequirements = Resource.Requirements(
      requests = List(
        jobManagerSettings.resources.cpuRequest.map(req => Resource.cpu       -> req),
        jobManagerSettings.resources.memoryRequest.map(req => Resource.memory -> req)
      ).flatten.toMap,
      limits = List(
        jobManagerSettings.resources.memoryLimit.map(lim => Resource.memory -> lim),
        jobManagerSettings.resources.cpuLimit.map(lim => Resource.cpu       -> lim)
      ).flatten.toMap
    )

    resourceRequirements = podsConfig.pods
      .get(PodsConfig.CloudflowPodName)
      .orElse(podsConfig.pods.get(podName))
      .flatMap { podConfig =>
        podConfig.containers.get(PodsConfig.CloudflowContainerName).map { containerConfig =>
          resourceRequirements.copy(
            limits = resourceRequirements.limits ++ containerConfig.resources.map(_.limits).getOrElse(Map()),
            requests = resourceRequirements.requests ++ containerConfig.resources.map(_.requests).getOrElse(Map())
          )
        }
      }
      .getOrElse(resourceRequirements)
    if (resourceRequirements.limits.nonEmpty || resourceRequirements.requests.nonEmpty) Some(resourceRequirements)
    else None
  }

  private def getTaskManagerResourceRequirements(podsConfig: PodsConfig,
                                                 podName: String)(implicit ctx: DeploymentContext): Option[Requirements] = {
    import ctx.flinkRunnerSettings._

    var resourceRequirements = Resource.Requirements(
      requests = List(
        taskManagerSettings.resources.cpuRequest.map(req => Resource.cpu       -> req),
        taskManagerSettings.resources.memoryRequest.map(req => Resource.memory -> req)
      ).flatten.toMap,
      limits = List(
        taskManagerSettings.resources.memoryLimit.map(lim => Resource.memory -> lim),
        taskManagerSettings.resources.cpuLimit.map(lim => Resource.cpu       -> lim)
      ).flatten.toMap
    )

    resourceRequirements = podsConfig.pods
      .get(PodsConfig.CloudflowPodName)
      .orElse(podsConfig.pods.get(podName))
      .flatMap { podConfig =>
        podConfig.containers.get(PodsConfig.CloudflowContainerName).map { containerConfig =>
          resourceRequirements.copy(
            limits = resourceRequirements.limits ++ containerConfig.resources.map(_.limits).getOrElse(Map()),
            requests = resourceRequirements.requests ++ containerConfig.resources.map(_.requests).getOrElse(Map())
          )
        }
      }
      .getOrElse(resourceRequirements)
    if (resourceRequirements.limits.nonEmpty || resourceRequirements.requests.nonEmpty) Some(resourceRequirements)
    else None
  }

  def getFlinkConfig(configSecret: Secret): Map[String, String] = {
    val conf = Try(getRuntimeConfig(configSecret).getConfig(runtime)).getOrElse(ConfigFactory.empty)
    if (conf.isEmpty) Map()
    else
      conf
        .entrySet()
        .asScala
        .map(entry => entry.getKey -> entry.getValue.unwrapped().toString)
        .toMap
  }

  /**
   * Flink treats config-map, secret and pvc claim as volumes.
   * // "volumes": [
   * //   {
   * //     "name": "config-vol",
   * //     "configMap": {
   * //       "name": "configmap-some-app-id"
   * //     }
   * //   },
   * //   {
   * //     "name": "my-secret",
   * //     "secret": {
   * //       "name": "flink-streamlet"
   * //     }
   * //   },
   * //   {
   * //     "name": "persistent-storage",
   * //     "persistentVolumeClaim": {
   * //       "name": "some-app-id-pvc"
   * //     }
   * //   }
   * // ]
   */
  private def makeVolumesSpec(deployment: StreamletDeployment,
                              app: CloudflowApplication.CR,
                              streamletToDeploy: Option[StreamletInstance]): Vector[Volume] = {
    // config map
    val configMapName   = Name.ofConfigMap(deployment.name)
    val configMapVolume = Volume("config-map-vol", Volume.ConfigMapVolumeSource(configMapName))

    // secret
    val secretVolume = Volume("secret-vol", Volume.Secret(deployment.secretName))

    // persistent storage
    val pvcName   = Name.ofPVCInstance(app.spec.appId)
    val pvcVolume = Volume("persistent-storage-vol", Volume.PersistentVolumeClaimRef(pvcName))

    // Streamlet volume mounting
    val streamletPvcVolume = streamletToDeploy.toVector.flatMap(_.descriptor.volumeMounts.map { mount ⇒
      Volume(mount.name, Volume.PersistentVolumeClaimRef(mount.pvcName))
    })

    streamletPvcVolume :+ configMapVolume :+ pvcVolume :+ secretVolume :+ Runner.DownwardApiVolume
  }

  /**
   * For every volume we need a volume mount spec
   * // "volumeMounts": [
   * //   {
   * //     "name": "persistent-storage",
   * //     "mountPath": "/mnt/flink/storage"
   * //   },
   * //   {
   * //     "name": "config-vol",
   * //     "mountPath": "/etc/cloudflow-runner"
   * //   },
   * //   {
   * //     "name": "my-secret",
   * //     "mountPath": "/etc/cloudflow-runner-secret"
   * //   }
   * // ]
   */
  private def makeVolumeMountsSpec(streamletToDeploy: Option[StreamletInstance]): Vector[Volume.Mount] = {
    val streamletVolumeMount = streamletToDeploy.toVector.flatMap(_.descriptor.volumeMounts.map { mount ⇒
      Volume.Mount(mount.name, mount.path)
    })

    Vector(
      Volume.Mount("persistent-storage-vol", "/mnt/flink/storage"),
      Volume.Mount("secret-vol", Runner.SecretMountPath),
      Volume.Mount("config-map-vol", "/etc/cloudflow-runner"),
      Runner.DownwardApiVolumeMount
    ) ++ streamletVolumeMount
  }
}

object FlinkResource {

  final case class SecurityContext(fsGroup: Option[Int])
  final case class HostPath(path: String, `type`: String)
  final case class NamePath(name: String, path: String)
  final case class NamePathSecretType(name: String, path: String, secretType: String = "Generic")

  final case class ResourceRequests(memory: Option[String] = None, cpu: Option[String] = None)
  object ResourceRequests {
    def make(memory: Option[String] = None, cpu: Option[String] = None): Option[ResourceRequests] = (memory, cpu) match {
      case (Some(_), Some(_)) ⇒ Some(ResourceRequests(memory, cpu))
      case (Some(_), None)    ⇒ Some(ResourceRequests(memory, None))
      case (None, Some(_))    ⇒ Some(ResourceRequests(None, cpu))
      case (None, None)       ⇒ None
    }
  }

  final case class ResourceLimits(memory: Option[String] = None, cpu: Option[String] = None)
  object ResourceLimits {
    def make(memory: Option[String] = None, cpu: Option[String] = None): Option[ResourceLimits] = (memory, cpu) match {
      case (Some(_), Some(_)) ⇒ Some(ResourceLimits(memory, cpu))
      case (Some(_), None)    ⇒ Some(ResourceLimits(memory, None))
      case (None, Some(_))    ⇒ Some(ResourceLimits(None, cpu))
      case (None, None)       ⇒ None
    }
  }

  final case class Resources(requests: Option[ResourceRequests] = None, limits: Option[ResourceLimits] = None)
  object Resources {
    def make(requests: Option[ResourceRequests] = None, limits: Option[ResourceLimits] = None): Option[Resources] =
      (requests, limits) match {
        case (Some(_), Some(_)) ⇒ Some(Resources(requests, limits))
        case (Some(_), None)    ⇒ Some(Resources(requests, None))
        case (None, Some(_))    ⇒ Some(Resources(None, limits))
        case (None, None)       ⇒ None
      }
  }

  final case class JobManagerConfig(
      replicas: Option[Int],
      resources: Option[Requirements] = None,
      envConfig: Option[EnvConfig]
  )

  final case class TaskManagerConfig(
      taskSlots: Option[Int],
      resources: Option[Requirements] = None,
      envConfig: Option[EnvConfig]
  )

  /*
  https://github.com/lyft/flinkk8soperator/blob/v0.5.0/pkg/apis/app/v1beta1/types.go

  type FlinkApplicationSpec struct {
    Image                         string                       `json:"image,omitempty" protobuf:"bytes,2,opt,name=image"`
    ImagePullPolicy               apiv1.PullPolicy             `json:"imagePullPolicy,omitempty" protobuf:"bytes,14,opt,name=imagePullPolicy,casttype=PullPolicy"`
    ImagePullSecrets              []apiv1.LocalObjectReference `json:"imagePullSecrets,omitempty" patchStrategy:"merge" patchMergeKey:"name" protobuf:"bytes,15,rep,name=imagePullSecrets"`
    ServiceAccountName            string                       `json:"serviceAccountName,omitempty"`
    FlinkConfig                   FlinkConfig                  `json:"flinkConfig"`
    FlinkVersion                  string                       `json:"flinkVersion"`
    TaskManagerConfig             TaskManagerConfig            `json:"taskManagerConfig,omitempty"`
    JobManagerConfig              JobManagerConfig             `json:"jobManagerConfig,omitempty"`
    JarName                       string                       `json:"jarName"`
    Parallelism                   int32                        `json:"parallelism"`
    EntryClass                    string                       `json:"entryClass,omitempty"`
    ProgramArgs                   string                       `json:"programArgs,omitempty"`
    // Deprecated: use SavepointPath instead
    SavepointInfo                  SavepointInfo               `json:"savepointInfo,omitempty"`
    SavepointPath                  string                      `json:"savepointPath,omitempty"`
    DeploymentMode                 DeploymentMode              `json:"deploymentMode,omitempty"`
    RPCPort                        *int32                      `json:"rpcPort,omitempty"`
    BlobPort                       *int32                      `json:"blobPort,omitempty"`
    QueryPort                      *int32                      `json:"queryPort,omitempty"`
    UIPort                         *int32                      `json:"uiPort,omitempty"`
    MetricsQueryPort               *int32                      `json:"metricsQueryPort,omitempty"`
    Volumes                        []apiv1.Volume              `json:"volumes,omitempty"`
    VolumeMounts                   []apiv1.VolumeMount         `json:"volumeMounts,omitempty"`
    RestartNonce                   string                      `json:"restartNonce"`
    DeleteMode                     DeleteMode                  `json:"deleteMode,omitempty"`
    AllowNonRestoredState          bool                        `json:"allowNonRestoredState,omitempty"`
    ForceRollback                  bool                        `json:"forceRollback"`
    MaxCheckpointRestoreAgeSeconds *int32                      `json:"maxCheckpointRestoreAgeSeconds,omitempty"`
  } */

  final case class Spec(
      image: String = "", // required parameter
      imagePullPolicy: String = "Always",
      flinkVersion: String = "1.10",
      serviceAccountName: String = Name.ofServiceAccount,
      jarName: String,
      parallelism: Int,
      entryClass: String = "",
      programArgs: Option[String] = None,
      deploymentMode: String = "Dual",
      volumes: Seq[Volume] = Seq.empty,
      flinkConfig: Map[String, String],
      jobManagerConfig: JobManagerConfig,
      taskManagerConfig: TaskManagerConfig,
      volumeMounts: Seq[Volume.Mount] = Seq.empty,
      restartNonce: String = ""
  )

  final case class ApplicationState(state: String, errorMessage: Option[String])
  final case class JobManagerInfo(
      podName: Option[String],
      webUIAddress: Option[String],
      webUIPort: Option[Int],
      webUIServiceName: Option[String]
  )
  final case class Status(
      appId: Option[String],
      applicationState: Option[ApplicationState],
      completionTime: Option[String],
      jobManagerInfo: Option[JobManagerInfo],
      submissionTime: Option[String] // may need to parse it as a date later on
  )

  implicit val hostPathFmt: Format[HostPath]               = Json.format[HostPath]
  implicit val securityContextFmt: Format[SecurityContext] = Json.format[SecurityContext]

  implicit val namePathFmt: Format[NamePath]                     = Json.format[NamePath]
  implicit val namePathSecretTypeFmt: Format[NamePathSecretType] = Json.format[NamePathSecretType]
  implicit val resourceRequsetsFmt: Format[ResourceRequests]     = Json.format[ResourceRequests]
  implicit val resourceLimitsFmt: Format[ResourceLimits]         = Json.format[ResourceLimits]
  implicit val envConfigFmt: Format[EnvConfig]                   = Json.format[EnvConfig]
  implicit val resourcesFmt: Format[Resources]                   = Json.format[Resources]

  implicit val jobManagerFmt: Format[JobManagerConfig]   = Json.format[JobManagerConfig]
  implicit val jobManagerInfoFmt: Format[JobManagerInfo] = Json.format[JobManagerInfo]
  implicit val taskManagerFmt: Format[TaskManagerConfig] = Json.format[TaskManagerConfig]

  implicit val specFmt: Format[Spec]                         = Json.format[Spec]
  implicit val applicationStateFmt: Format[ApplicationState] = Json.format[ApplicationState]
  implicit val statusFmt: Format[Status]                     = Json.format[Status]

  final case class EnvConfig(env: Option[List[EnvVar]])

  type CR = CustomResource[Spec, Status]

  implicit val resourceDefinition: ResourceDefinition[CustomResource[Spec, Status]] = ResourceDefinition[CR](
    group = "flink.k8s.io",
    version = "v1beta1",
    kind = "FlinkApplication",
    subresources = Some(Subresources().withStatusSubresource)
  )

  implicit val statusSubEnabled = CustomResource.statusMethodsEnabler[CR]
}

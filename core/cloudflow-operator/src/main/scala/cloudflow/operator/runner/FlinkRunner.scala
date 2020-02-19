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

import play.api.libs.json._
import skuber._
import cloudflow.blueprint.deployment._
import FlinkResource._
import skuber.ResourceSpecification.Subresources

/**
 * Creates the ConfigMap and the Runner resource (a FlinkResource.CR) that define a Spark [[Runner]].
 */
object FlinkRunner extends Runner[CR] {
  def format = implicitly[Format[CR]]
  def editor = new ObjectEditor[CR] {
    override def updateMetadata(obj: CR, newMetadata: ObjectMeta) = obj.copy(metadata = newMetadata)
  }
  def configEditor = new ObjectEditor[ConfigMap] {
    override def updateMetadata(obj: ConfigMap, newMetadata: ObjectMeta) = obj.copy(metadata = newMetadata)
  }

  def resourceDefinition       = implicitly[ResourceDefinition[CR]]
  final val runtime            = "flink"
  final val PVCMountPath       = "/mnt/flink/storage"
  final val DefaultParallelism = 2
  final val JvmArgsEnvVar      = "JVM_ARGS"

  def resource(
      deployment: StreamletDeployment,
      app: CloudflowApplication.CR,
      namespace: String,
      updateLabels: Map[String, String]
  )(implicit ctx: DeploymentContext): CR = {

    val image             = deployment.image
    val streamletToDeploy = app.spec.streamlets.find(streamlet ⇒ streamlet.name == deployment.streamletName)

    val volumes      = makeVolumesSpec(deployment, app, streamletToDeploy)
    val volumeMounts = makeVolumeMountsSpec(streamletToDeploy)

    import ctx.flinkRunnerSettings._

    val envConfig = EnvConfig(
      List(
        EnvVar(JvmArgsEnvVar, makePrometheusAgentJvmArgs(app))
      )
    )

    val jobManagerConfig = JobManagerConfig(
      jobManagerSettings.replicas,
      Resources.make(
        ResourceRequests.make(jobManagerSettings.resources.memoryRequest.map(_.value),
                              jobManagerSettings.resources.cpuRequest.map(_.value)),
        ResourceLimits.make(jobManagerSettings.resources.memoryLimit.map(_.value), jobManagerSettings.resources.cpuLimit.map(_.value))
      ),
      envConfig
    )

    val scale = deployment.replicas

    val taskManagerConfig = TaskManagerConfig(
      taskSlots = taskManagerSettings.taskSlots,
      Resources.make(
        ResourceRequests.make(taskManagerSettings.resources.memoryRequest.map(_.value),
                              taskManagerSettings.resources.cpuRequest.map(_.value)),
        ResourceLimits.make(taskManagerSettings.resources.memoryLimit.map(_.value), taskManagerSettings.resources.cpuLimit.map(_.value))
      ),
      envConfig
    )

    val flinkConfig: Map[String, String] = Map(
      "state.backend"                  -> "filesystem",
      "state.backend.fs.checkpointdir" -> s"file://${PVCMountPath}/checkpoints/${deployment.streamletName}",
      "state.checkpoints.dir"          -> s"file://${PVCMountPath}/externalized-checkpoints/${deployment.streamletName}",
      "state.savepoints.dir"           -> s"file://${PVCMountPath}/savepoints/${deployment.streamletName}"
    )

    val jobSpec = Spec(
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

    val name      = Name.ofFlinkApplication(deployment.name)
    val appLabels = CloudflowLabels(app)
    val labels = appLabels.withComponent(name, CloudflowLabels.StreamletComponent) ++ updateLabels ++
          Map(Operator.StreamletNameLabel -> deployment.streamletName, Operator.AppIdLabel -> app.spec.appId)
    val ownerReferences = CloudflowOwnerReferences(app)

    CustomResource[Spec, Status](jobSpec)
      .withMetadata(
        ObjectMeta(
          name = name,
          namespace = namespace,
          annotations = Map("prometheus.io/scrape" -> "true", "prometheus.io/port" -> PrometheusConfig.PrometheusJmxExporterPort.toString),
          labels = labels,
          ownerReferences = ownerReferences.list
        )
      )
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

  private def makePrometheusAgentJvmArgs(app: CloudflowApplication.CR): String = {
    val agentPath  = app.spec.agentPaths(CloudflowApplication.PrometheusAgentKey)
    val port       = PrometheusConfig.PrometheusJmxExporterPort.toString
    val configPath = PrometheusConfig.prometheusConfigPath(Runner.ConfigMapMountPath)
    s"-javaagent:$agentPath=$port:$configPath"
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
      replicas: Int,
      resources: Option[Resources] = None,
      envConfig: EnvConfig = EnvConfig()
  )

  final case class TaskManagerConfig(
      taskSlots: Int,
      resources: Option[Resources] = None,
      envConfig: EnvConfig = EnvConfig()
  )

  /*
  https://github.com/lyft/flinkk8soperator/blob/master/pkg/apis/app/v1alpha1/types.go

  type FlinkApplicationSpec struct {
    Image             string                       `json:"image,omitempty" protobuf:"bytes,2,opt,name=image"`
    ImagePullPolicy   apiv1.PullPolicy             `json:"imagePullPolicy,omitempty" protobuf:"bytes,14,opt,name=imagePullPolicy,casttype=PullPolicy"`
    ImagePullSecrets  []apiv1.LocalObjectReference `json:"imagePullSecrets,omitempty" patchStrategy:"merge" patchMergeKey:"name" protobuf:"bytes,15,rep,name=imagePullSecrets"`
    FlinkConfig       FlinkConfig                  `json:"flinkConfig"`
    FlinkVersion      string                       `json:"flinkVersion"`
    TaskManagerConfig TaskManagerConfig            `json:"taskManagerConfig,omitempty"`
    JobManagerConfig  JobManagerConfig             `json:"jobManagerConfig,omitempty"`
    JarName           string                       `json:"jarName"`
    Parallelism       int32                        `json:"parallelism"`
    EntryClass        string                       `json:"entryClass,omitempty"`
    ProgramArgs       string                       `json:"programArgs,omitempty"`
    SavepointInfo     SavepointInfo                `json:"savepointInfo,omitempty"`
    DeploymentMode    DeploymentMode               `json:"deploymentMode,omitempty"`
    RPCPort           *int32                       `json:"rpcPort,omitempty"`
    BlobPort          *int32                       `json:"blobPort,omitempty"`
    QueryPort         *int32                       `json:"queryPort,omitempty"`
    UIPort            *int32                       `json:"uiPort,omitempty"`
    MetricsQueryPort  *int32                       `json:"metricsQueryPort,omitempty"`
    Volumes           []apiv1.Volume               `json:"volumes,omitempty"`
    VolumeMounts      []apiv1.VolumeMount          `json:"volumeMounts,omitempty"`
    RestartNonce      string                       `json:"restartNonce"`
    DeleteMode        DeleteMode                   `json:"deleteMode,omitempty"`
  } */

  final case class Spec(
      image: String = "", // required parameter
      imagePullPolicy: String = "Always",
      flinkVersion: String = "1.8",
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

  implicit val volumeMountFmt: Format[Volume.Mount]        = skuber.json.format.volMountFormat
  implicit val volumeFmt: Format[Volume]                   = skuber.json.format.volumeFormat
  implicit val envVarFmt: Format[EnvVar]                   = skuber.json.format.envVarFormat
  implicit val hostPathFmt: Format[HostPath]               = Json.format[HostPath]
  implicit val securityContextFmt: Format[SecurityContext] = Json.format[SecurityContext]

  implicit val namePathFmt: Format[NamePath]                     = Json.format[NamePath]
  implicit val namePathSecretTypeFmt: Format[NamePathSecretType] = Json.format[NamePathSecretType]
  implicit val resourceRequsetsFmt: Format[ResourceRequests]     = Json.format[ResourceRequests]
  implicit val resourceLimitsFmt: Format[ResourceLimits]         = Json.format[ResourceLimits]
  implicit val resourcesFmt: Format[Resources]                   = Json.format[Resources]
  implicit val envConfigFmt: Format[EnvConfig]                   = Json.format[EnvConfig]

  implicit val jobManagerFmt: Format[JobManagerConfig]   = Json.format[JobManagerConfig]
  implicit val taskManagerFmt: Format[TaskManagerConfig] = Json.format[TaskManagerConfig]

  implicit val specFmt: Format[Spec]     = Json.format[Spec]
  implicit val statusFmt: Format[Status] = Json.format[Status]

  final case class EnvConfig(env: List[EnvVar] = Nil)

  type CR = CustomResource[Spec, Status]

  implicit val applicationStateFmt: Format[ApplicationState] = Json.format[ApplicationState]
  implicit val jobManagerInfoFmt: Format[JobManagerInfo]     = Json.format[JobManagerInfo]

  implicit val resourceDefinition: ResourceDefinition[CustomResource[Spec, Status]] = ResourceDefinition[CR](
    group = "flink.k8s.io",
    version = "v1beta1",
    kind = "FlinkApplication",
    subresources = Some(Subresources().withStatusSubresource)
  )

  implicit val statusSubEnabled = CustomResource.statusMethodsEnabler[CR]
}

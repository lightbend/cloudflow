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

import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters._

import scala.util.Try
import com.typesafe.config._
import play.api.libs.json._
import skuber.json.format._
import skuber.json.rbac.format._
import skuber.rbac._
import skuber.Resource._
import skuber.ResourceSpecification.Subresources
import skuber._
import cloudflow.blueprint.deployment._
import FlinkResource._

import cloudflow.operator.action._
import cloudflow.operator.action.Action

object FlinkRunner {
  final val Runtime                    = "flink"
  final val PVCMountPath               = "/mnt/flink/storage"
  final val DefaultTaskManagerReplicas = 1
  final val DefaultJobManagerReplicas  = 1

  final val JobManagerPod  = "job-manager"
  final val TaskManagerPod = "task-manager"
}

/**
 * Creates the ConfigMap and the Runner resource (a FlinkResource.CR) that define a Flink [[Runner]].
 */
final class FlinkRunner(flinkRunnerDefaults: FlinkRunnerDefaults) extends Runner[CR] {
  import FlinkRunner._
  import flinkRunnerDefaults._

  var nrOfJobManagers  = new AtomicReference(Map[String, Int]()) //DefaultJobManagerReplicas
  var nrOfTaskManagers = new AtomicReference(Map[String, Int]()) //DefaultTaskManagerReplicas
  var parallelism      = new AtomicReference(Map[String, Int]()) //flinkRunnerDefaults.parallelism

  def format  = implicitly[Format[CR]]
  val runtime = Runtime
  def editor = new ObjectEditor[CR] {
    override def updateMetadata(obj: CR, newMetadata: ObjectMeta) = obj.copy(metadata = newMetadata)
  }
  def configEditor = new ObjectEditor[ConfigMap] {
    override def updateMetadata(obj: ConfigMap, newMetadata: ObjectMeta) = obj.copy(metadata = newMetadata)
  }

  def resourceDefinition = implicitly[ResourceDefinition[CR]]
  def prometheusConfig   = PrometheusConfig(prometheusRules)

  def appActions(app: CloudflowApplication.CR, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): Seq[Action] = {
    val roleFlink = flinkRole(app.namespace, labels, ownerReferences)
    Vector(
      Action.createOrUpdate(roleFlink, app, roleEditor),
      Action.createOrUpdate(flinkRoleBinding(app.namespace, roleFlink, labels, ownerReferences), app, roleBindingEditor)
    )
  }

  def streamletChangeAction(app: CloudflowApplication.CR,
                            runners: Map[String, Runner[_]],
                            streamletDeployment: StreamletDeployment,
                            secret: skuber.Secret) = {
    val updateLabels = Map(CloudflowLabels.ConfigUpdateLabel -> System.currentTimeMillis.toString)

    val _resource =
      resource(streamletDeployment, app, secret, updateLabels)
    val labeledResource =
      _resource.copy(metadata = _resource.metadata.copy(labels = _resource.metadata.labels ++ updateLabels))
    Action.createOrUpdate(labeledResource, app, editor)
  }

  def defaultReplicas = DefaultTaskManagerReplicas

  def expectedPodCount(deployment: StreamletDeployment) =
    deployment.replicas.getOrElse(parallelism.get().get(deployment.name).getOrElse(FlinkRunner.DefaultTaskManagerReplicas)) + nrOfJobManagers
          .get()
          .get(deployment.name)
          .getOrElse(DefaultJobManagerReplicas)

  private def flinkRole(namespace: String, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): Role =
    Role(
      metadata = ObjectMeta(
        name = Name.ofFlinkRole,
        namespace = namespace,
        labels = labels(Name.ofFlinkRole),
        ownerReferences = ownerReferences
      ),
      kind = "Role",
      rules = List(
        PolicyRule(
          apiGroups = List(""),
          attributeRestrictions = None,
          nonResourceURLs = List(),
          resourceNames = List(),
          resources = List("pods", "services", "configmaps", "ingresses", "endpoints"),
          verbs = List("get", "create", "delete", "list", "watch", "update")
        ),
        createEventPolicyRule
      )
    )

  private def flinkRoleBinding(namespace: String, role: Role, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): RoleBinding =
    RoleBinding(
      metadata = ObjectMeta(
        name = Name.ofFlinkRoleBinding,
        namespace = namespace,
        labels = labels(Name.ofRoleBinding),
        ownerReferences = ownerReferences
      ),
      kind = "RoleBinding",
      roleRef = RoleRef("rbac.authorization.k8s.io", "Role", role.metadata.name),
      subjects = List(
        Subject(
          None,
          "ServiceAccount",
          Name.ofServiceAccount,
          Some(namespace)
        )
      )
    )

  def resource(
      deployment: StreamletDeployment,
      app: CloudflowApplication.CR,
      configSecret: Secret,
      updateLabels: Map[String, String] = Map()
  ): CR = {
    val podsConfig = getPodsConfig(configSecret)

    val javaOptions = getJavaOptions(podsConfig, PodsConfig.CloudflowPodName)

    val image             = deployment.image
    val streamletToDeploy = app.spec.streamlets.find(streamlet => streamlet.name == deployment.streamletName)

    val dockerContainerGroupId = Runner.DockerContainerGroupId
    val securityContext =
      if (streamletToDeploy.exists(_.descriptor.volumeMounts.exists(_.accessMode == "ReadWriteMany")))
        Some(PodSecurityContext(fsGroup = Some(dockerContainerGroupId)))
      else
        None

    val volumes      = makeVolumesSpec(deployment, streamletToDeploy) ++ getVolumes(podsConfig, PodsConfig.CloudflowPodName)
    val volumeMounts = makeVolumeMountsSpec(streamletToDeploy) ++ getVolumeMounts(podsConfig, PodsConfig.CloudflowPodName)

    val flinkConfig: Map[String, String] = Map(
        "state.backend"                    -> "filesystem",
        "state.backend.fs.checkpointdir"   -> s"file://${PVCMountPath}/checkpoints/${deployment.streamletName}",
        "state.checkpoints.dir"            -> s"file://${PVCMountPath}/externalized-checkpoints/${deployment.streamletName}",
        "state.savepoints.dir"             -> s"file://${PVCMountPath}/savepoints/${deployment.streamletName}"
      ) ++ javaOptions.map("env.java.opts" -> _) ++ getFlinkConfig(configSecret)

    val nrOfJobManagersForResource = flinkConfig
    // This adds configuration option that does not exist in Flink,
    // but allows users to configure number of jobmanagers and try out HA options.
      .get("jobmanager.replicas")
      .flatMap(configuredJobManagerReplicas => Try(configuredJobManagerReplicas.toInt).toOption)
      .getOrElse(jobManagerDefaults.replicas)
    nrOfJobManagers.getAndUpdate(old => old + (deployment.name -> nrOfJobManagersForResource))
    val jobManagerConfig = JobManagerConfig(
      Some(
        nrOfJobManagersForResource
      ),
      getJobManagerResourceRequirements(podsConfig, JobManagerPod),
      Some(EnvConfig(getEnvironmentVariables(podsConfig, JobManagerPod)))
    )

    val scale = deployment.replicas
    val nrOfTaskManagersForResource = flinkConfig
      .get("taskmanager.numberOfTaskSlots")
      .flatMap(configuredSlots => Try(configuredSlots.toInt).toOption)
      .getOrElse(taskManagerDefaults.taskSlots)
    nrOfTaskManagers.getAndUpdate(old => old + (deployment.name -> nrOfTaskManagersForResource))

    val taskManagerConfig = TaskManagerConfig(
      Some(
        nrOfTaskManagersForResource
      ),
      getTaskManagerResourceRequirements(podsConfig, TaskManagerPod),
      Some(EnvConfig(getEnvironmentVariables(podsConfig, TaskManagerPod)))
    )
    val parallelismForResource = scale
      .map(_ * taskManagerDefaults.taskSlots)
      .getOrElse(
        flinkConfig
          .get("parallelism.default")
          .flatMap(configuredParallelism => Try(configuredParallelism.toInt).toOption)
          .getOrElse(flinkRunnerDefaults.parallelism)
      )
    parallelism.getAndUpdate(old => old + (deployment.name -> parallelismForResource))
    val _spec = Spec(
      image = image,
      jarName = RunnerJarName,
      securityContext = securityContext,
      parallelism = parallelismForResource,
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
          Map(CloudflowLabels.StreamletNameLabel -> deployment.streamletName, CloudflowLabels.AppIdLabel -> app.spec.appId).view
            .mapValues(Name.ofLabelValue) ++
          getLabels(podsConfig, PodsConfig.CloudflowPodName)
    val ownerReferences = List(OwnerReference(app.apiVersion, app.kind, app.metadata.name, app.metadata.uid, Some(true), Some(true)))

    CustomResource[Spec, Status](_spec)
      .withMetadata(
        ObjectMeta(
          name = name,
          namespace = app.namespace,
          annotations = Map("prometheus.io/scrape" -> "true", "prometheus.io/port" -> PrometheusConfig.PrometheusJmxExporterPort.toString),
          labels = labels,
          ownerReferences = ownerReferences
        )
      )
  }

  def resourceName(deployment: StreamletDeployment): String = Name.ofFlinkApplication(deployment.name)

  private def getJobManagerResourceRequirements(podsConfig: PodsConfig, podName: String): Option[Requirements] = {

    var resourceRequirements = Resource.Requirements(
      requests = List(
        jobManagerDefaults.resources.cpuRequest.map(req => Resource.cpu       -> req),
        jobManagerDefaults.resources.memoryRequest.map(req => Resource.memory -> req)
      ).flatten.toMap,
      limits = List(
        jobManagerDefaults.resources.memoryLimit.map(lim => Resource.memory -> lim),
        jobManagerDefaults.resources.cpuLimit.map(lim => Resource.cpu       -> lim)
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

  private def getTaskManagerResourceRequirements(podsConfig: PodsConfig, podName: String): Option[Requirements] = {

    var resourceRequirements = Resource.Requirements(
      requests = List(
        taskManagerDefaults.resources.cpuRequest.map(req => Resource.cpu       -> req),
        taskManagerDefaults.resources.memoryRequest.map(req => Resource.memory -> req)
      ).flatten.toMap,
      limits = List(
        taskManagerDefaults.resources.memoryLimit.map(lim => Resource.memory -> lim),
        taskManagerDefaults.resources.cpuLimit.map(lim => Resource.cpu       -> lim)
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
  private def makeVolumesSpec(deployment: StreamletDeployment, streamletToDeploy: Option[StreamletInstance]): Vector[Volume] = {
    // config map
    val configMapName   = Name.ofConfigMap(deployment.name)
    val configMapVolume = Volume("config-map-vol", Volume.ConfigMapVolumeSource(configMapName))

    // secret
    val secretVolume = Volume("secret-vol", Volume.Secret(deployment.secretName))

    // Streamlet volume mounting (Defined by Streamlet.volumeMounts API)
    val streamletPvcVolume = streamletToDeploy.toVector.flatMap(_.descriptor.volumeMounts.map { mount =>
      Volume(mount.name, Volume.PersistentVolumeClaimRef(mount.pvcName))
    })

    streamletPvcVolume :+ configMapVolume :+ secretVolume :+ Runner.DownwardApiVolume
  }

  /**
   * For every volume we need a volume mount spec
   * // "volumeMounts": [
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
    val streamletVolumeMount = streamletToDeploy.toVector.flatMap(_.descriptor.volumeMounts.map { mount =>
      Volume.Mount(mount.name, mount.path)
    })

    Vector(
      Volume.Mount("secret-vol", Runner.SecretMountPath),
      Volume.Mount("config-map-vol", "/etc/cloudflow-runner"),
      Runner.DownwardApiVolumeMount
    ) ++ streamletVolumeMount
  }
}

object FlinkResource {

  final case class HostPath(path: String, `type`: String)
  final case class NamePath(name: String, path: String)
  final case class NamePathSecretType(name: String, path: String, secretType: String = "Generic")

  final case class ResourceRequests(memory: Option[String] = None, cpu: Option[String] = None)
  object ResourceRequests {
    def make(memory: Option[String] = None, cpu: Option[String] = None): Option[ResourceRequests] = (memory, cpu) match {
      case (Some(_), Some(_)) => Some(ResourceRequests(memory, cpu))
      case (Some(_), None)    => Some(ResourceRequests(memory, None))
      case (None, Some(_))    => Some(ResourceRequests(None, cpu))
      case (None, None)       => None
    }
  }

  final case class ResourceLimits(memory: Option[String] = None, cpu: Option[String] = None)
  object ResourceLimits {
    def make(memory: Option[String] = None, cpu: Option[String] = None): Option[ResourceLimits] = (memory, cpu) match {
      case (Some(_), Some(_)) => Some(ResourceLimits(memory, cpu))
      case (Some(_), None)    => Some(ResourceLimits(memory, None))
      case (None, Some(_))    => Some(ResourceLimits(None, cpu))
      case (None, None)       => None
    }
  }

  final case class Resources(requests: Option[ResourceRequests] = None, limits: Option[ResourceLimits] = None)
  object Resources {
    def make(requests: Option[ResourceRequests] = None, limits: Option[ResourceLimits] = None): Option[Resources] =
      (requests, limits) match {
        case (Some(_), Some(_)) => Some(Resources(requests, limits))
        case (Some(_), None)    => Some(Resources(requests, None))
        case (None, Some(_))    => Some(Resources(None, limits))
        case (None, None)       => None
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
    SecurityContext               *apiv1.PodSecurityContext    `json:"securityContext,omitempty"`
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
    SavepointDisabled              bool                        `json:"savepointDisabled"`
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
    TearDownVersionHash            string                      `json:"tearDownVersionHash,omitempty"`
  }
   */

  final case class Spec(
      image: String = "", // required parameter
      imagePullPolicy: String = "Always",
      flinkVersion: String = "1.10",
      serviceAccountName: String = Name.ofServiceAccount,
      securityContext: Option[PodSecurityContext] = None,
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

  implicit val hostPathFmt: Format[HostPath] = Json.format[HostPath]

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
    subresources = Some(Subresources().withStatusSubresource())
  )

  implicit val statusSubEnabled = CustomResource.statusMethodsEnabler[CR]
}

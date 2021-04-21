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

import akka.datap.crd.App
import akka.kube.actions.{ Action, CustomResourceAdapter }
import cloudflow.blueprint.deployment.PrometheusConfig
import cloudflow.operator.action._
import com.fasterxml.jackson.annotation.{ JsonCreator, JsonIgnoreProperties, JsonProperty }
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.typesafe.config._
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.api.model.rbac._
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext
import io.fabric8.kubernetes.client.{ CustomResource, CustomResourceList }
import io.fabric8.kubernetes.model.annotation.{ Group, Kind, Plural, Version }

import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag
import scala.util.Try

object FlinkRunner {
  final val Runtime = "flink"
  final val PVCMountPath = "/mnt/flink/storage"
  final val DefaultTaskManagerReplicas = 1
  final val DefaultJobManagerReplicas = 1

  final val JobManagerPod = "job-manager"
  final val TaskManagerPod = "task-manager"
}

/**
 * Creates the ConfigMap and the Runner resource (a FlinkResource.CR) that define a Flink [[Runner]].
 */
final class FlinkRunner(flinkRunnerDefaults: FlinkRunnerDefaults) extends Runner[FlinkApp.Cr] {
  import FlinkRunner._
  import flinkRunnerDefaults._
  val runtime = Runtime

  implicit val adapter =
    CustomResourceAdapter[FlinkApp.Cr, FlinkApp.List](FlinkApp.customResourceDefinitionContext)

  val nrOfJobManagers = new AtomicReference(Map[String, Int]()) //DefaultJobManagerReplicas
  val nrOfTaskManagers = new AtomicReference(Map[String, Int]()) //DefaultTaskManagerReplicas
  val parallelism = new AtomicReference(Map[String, Int]()) //flinkRunnerDefaults.parallelism

  def appActions(app: App.Cr, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): Seq[Action] = {
    val roleFlink = flinkRole(app.namespace, labels, ownerReferences)
    Vector(
      Action.createOrReplace(roleFlink),
      Action.createOrReplace(flinkRoleBinding(app.namespace, roleFlink, labels, ownerReferences)))
  }

  def streamletChangeAction(
      app: App.Cr,
      runners: Map[String, Runner[_]],
      streamletDeployment: App.Deployment,
      secret: Secret) = {
    val updateLabels = Map(CloudflowLabels.ConfigUpdateLabel -> System.currentTimeMillis.toString)

    val res = resource(streamletDeployment, app, secret, updateLabels)
    val metadata = res.getMetadata
    metadata.setLabels((metadata.getLabels.asScala ++ updateLabels).asJava)
    res.setMetadata(metadata)

    Action.Cr.createOrReplace(res)
  }

  def defaultReplicas = DefaultTaskManagerReplicas

  def expectedPodCount(deployment: App.Deployment) =
    deployment.replicas.getOrElse(
      parallelism.get().get(deployment.name).getOrElse(FlinkRunner.DefaultTaskManagerReplicas)) + nrOfJobManagers
      .get()
      .get(deployment.name)
      .getOrElse(DefaultJobManagerReplicas)

  private def flinkRole(namespace: String, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): Role = {
    new RoleBuilder()
      .withNewMetadata()
      .withName(Name.ofFlinkRole)
      .withNamespace(namespace)
      .withLabels(labels(Name.ofFlinkRole).asJava)
      .withOwnerReferences(ownerReferences: _*)
      .endMetadata()
      .withKind("Role")
      .withRules(
        new PolicyRuleBuilder()
          .withApiGroups("")
          .withResources("pods", "services", "configmaps", "ingresses", "endpoints")
          .withVerbs("get", "create", "delete", "list", "watch", "update")
          .build(),
        createEventPolicyRule)
      .build()
  }

  private def flinkRoleBinding(
      namespace: String,
      role: Role,
      labels: CloudflowLabels,
      ownerReferences: List[OwnerReference]): RoleBinding = {
    new RoleBindingBuilder()
      .withNewMetadata()
      .withName(Name.ofFlinkRoleBinding)
      .withNamespace(namespace)
      .withLabels(labels(Name.ofRoleBinding).asJava)
      .withOwnerReferences(ownerReferences: _*)
      .endMetadata()
      .withKind("RoleBinding")
      .withNewRoleRef("rbac.authorization.k8s.io", "Role", role.getMetadata.getName)
      .withSubjects(
        new SubjectBuilder()
          .withKind("ServiceAccount")
          .withName(Name.ofServiceAccount)
          .withNamespace(namespace)
          .build())
      .build()
  }

  def resource(
      deployment: App.Deployment,
      app: App.Cr,
      configSecret: Secret,
      updateLabels: Map[String, String] = Map()): FlinkApp.Cr = {
    val podsConfig = getPodsConfig(configSecret)

    val javaOptions = getJavaOptions(podsConfig, PodsConfig.CloudflowPodName)

    val image = deployment.image
    val streamletToDeploy = app.spec.streamlets.find(streamlet => streamlet.name == deployment.streamletName)

    val dockerContainerGroupId = Runner.DockerContainerGroupId
    val securityContext =
      if (streamletToDeploy.exists(_.descriptor.volumeMounts.exists(_.accessMode == "ReadWriteMany"))) {
        Some(
          new PodSecurityContextBuilder()
            .withFsGroup(dockerContainerGroupId)
            .build())
      } else
        None

    val volumes = makeVolumesSpec(deployment, streamletToDeploy) ++ getVolumes(podsConfig, PodsConfig.CloudflowPodName)
    val volumeMounts =
      makeVolumeMountsSpec(streamletToDeploy) ++ getVolumeMounts(podsConfig, PodsConfig.CloudflowPodName)

    val flinkConfig: Map[String, String] = Map(
        "state.backend" -> "filesystem",
        "state.backend.fs.checkpointdir" -> s"file://${PVCMountPath}/checkpoints/${deployment.streamletName}",
        "state.checkpoints.dir" -> s"file://${PVCMountPath}/externalized-checkpoints/${deployment.streamletName}",
        "state.savepoints.dir" -> s"file://${PVCMountPath}/savepoints/${deployment.streamletName}") ++ javaOptions.map(
        "env.java.opts" -> _) ++ getFlinkConfig(configSecret)

    val nrOfJobManagersForResource = flinkConfig
    // This adds configuration option that does not exist in Flink,
    // but allows users to configure number of jobmanagers and try out HA options.
      .get("jobmanager.replicas")
      .flatMap(configuredJobManagerReplicas => Try(configuredJobManagerReplicas.toInt).toOption)
      .getOrElse(jobManagerDefaults.replicas)
    nrOfJobManagers.getAndUpdate(old => old + (deployment.name -> nrOfJobManagersForResource))
    val jobManagerConfig = FlinkApp.JobManagerConfig(
      Some(nrOfJobManagersForResource),
      getJobManagerResourceRequirements(podsConfig, JobManagerPod),
      Some(FlinkApp.EnvConfig(getEnvironmentVariables(podsConfig, JobManagerPod))))

    val scale = deployment.replicas
    val nrOfTaskManagersForResource = flinkConfig
      .get("taskmanager.numberOfTaskSlots")
      .flatMap(configuredSlots => Try(configuredSlots.toInt).toOption)
      .getOrElse(taskManagerDefaults.taskSlots)
    nrOfTaskManagers.getAndUpdate(old => old + (deployment.name -> nrOfTaskManagersForResource))

    val taskManagerConfig = FlinkApp.TaskManagerConfig(
      Some(nrOfTaskManagersForResource),
      getTaskManagerResourceRequirements(podsConfig, TaskManagerPod),
      Some(FlinkApp.EnvConfig(getEnvironmentVariables(podsConfig, TaskManagerPod))))
    val parallelismForResource = scale
      .map(_ * taskManagerDefaults.taskSlots)
      .getOrElse(
        flinkConfig
          .get("parallelism.default")
          .flatMap(configuredParallelism => Try(configuredParallelism.toInt).toOption)
          .getOrElse(flinkRunnerDefaults.parallelism))
    parallelism.getAndUpdate(old => old + (deployment.name -> parallelismForResource))
    val _spec = FlinkApp.Spec(
      image = image,
      jarName = RunnerJarName,
      securityContext = securityContext,
      parallelism = parallelismForResource,
      entryClass = RuntimeMainClass,
      volumes = volumes,
      volumeMounts = volumeMounts,
      flinkConfig = flinkConfig,
      jobManagerConfig = jobManagerConfig,
      taskManagerConfig = taskManagerConfig)

    val name = resourceName(deployment)
    val appLabels = CloudflowLabels(app)
    val labels = appLabels.withComponent(name, CloudflowLabels.StreamletComponent) ++ updateLabels ++
      Map(CloudflowLabels.StreamletNameLabel -> deployment.streamletName, CloudflowLabels.AppIdLabel -> app.spec.appId).view
        .mapValues(Name.ofLabelValue) ++
      getLabels(podsConfig, PodsConfig.CloudflowPodName)
    val ownerReferences = List(AppOwnerReference(app.getMetadata.getName, app.getMetadata.getUid))

    val metadata = new ObjectMetaBuilder()
      .withName(name)
      .withNamespace(app.namespace)
      .withAnnotations(
        (Map(
          "prometheus.io/scrape" -> "true",
          "prometheus.io/port" -> PrometheusConfig.PrometheusJmxExporterPort.toString) ++ getAnnotations(
          podsConfig,
          PodsConfig.CloudflowPodName)).asJava)
      .withLabels(labels.asJava)
      .withOwnerReferences(ownerReferences: _*)
      .build()

    FlinkApp.Cr(metadata = metadata, spec = _spec)
  }

  def resourceName(deployment: App.Deployment): String = Name.ofFlinkApplication(deployment.name)

  override def deleteResource(name: String, namespace: String)(implicit ct: ClassTag[FlinkApp.Cr]): Action =
    Action.Cr.delete(name, namespace)

  override def createOrReplaceResource(res: FlinkApp.Cr)(implicit ct: ClassTag[FlinkApp.Cr]): Action = {
    Action.Cr.createOrReplace(res)
  }

  private def getResourceRequirements(
      resourceDefaults: FlinkPodResourceDefaults,
      podsConfig: PodsConfig,
      podName: String): Option[ResourceRequirements] = {

    var resReqManagerBuilder = new ResourceRequirementsBuilder()

    val defaultRequests =
      (resourceDefaults.cpuRequest.map(req => Map("cpu" -> req)).getOrElse(Map.empty[String, Quantity])) ++
      (resourceDefaults.memoryRequest.map(req => Map("memory" -> req)).getOrElse(Map.empty[String, Quantity]))

    resReqManagerBuilder = resReqManagerBuilder.withRequests(defaultRequests.asJava)

    val defaultLimits =
      (resourceDefaults.cpuLimit.map(req => Map("cpu" -> req)).getOrElse(Map.empty[String, Quantity])) ++
      (resourceDefaults.memoryLimit.map(req => Map("memory" -> req)).getOrElse(Map.empty[String, Quantity]))

    resReqManagerBuilder = resReqManagerBuilder.withLimits(defaultLimits.asJava)

    val _resourceRequirements = resReqManagerBuilder.build()

    val resourceRequirements = podsConfig.pods
      .get(PodsConfig.CloudflowPodName)
      .orElse(podsConfig.pods.get(podName))
      .flatMap { podConfig =>
        podConfig.containers.get(PodsConfig.CloudflowContainerName).map { containerConfig =>
          val req = _resourceRequirements.getRequests
          val lim = _resourceRequirements.getLimits

          containerConfig.resources.foreach { res =>
            if (res.getRequests != null) {
              req.putAll(res.getRequests)
            }
            if (res.getLimits != null) {
              lim.putAll(res.getLimits)
            }
          }

          new ResourceRequirementsBuilder()
            .withRequests(req)
            .withLimits(lim)
            .build()
        }
      }
      .getOrElse(_resourceRequirements)
    if ((resourceRequirements.getLimits != null && !resourceRequirements.getLimits.isEmpty) || (resourceRequirements.getRequests != null && !resourceRequirements.getRequests.isEmpty))
      Some(resourceRequirements)
    else None
  }

  private def getJobManagerResourceRequirements(podsConfig: PodsConfig, podName: String): Option[ResourceRequirements] =
    getResourceRequirements(jobManagerDefaults.resources, podsConfig, podName)

  private def getTaskManagerResourceRequirements(
      podsConfig: PodsConfig,
      podName: String): Option[ResourceRequirements] =
    getResourceRequirements(taskManagerDefaults.resources, podsConfig, podName)

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
  private def makeVolumesSpec(deployment: App.Deployment, streamletToDeploy: Option[App.Streamlet]): Vector[Volume] = {
    // secret
    val secretVolume = {
      new VolumeBuilder()
        .withName("secret-vol")
        .withNewSecret()
        .withSecretName(deployment.secretName)
        .endSecret()
        .build()
    }

    // Streamlet volume mounting (Defined by Streamlet.volumeMounts API)
    val streamletPvcVolume = streamletToDeploy.toVector.flatMap(_.descriptor.volumeMounts.map { mount =>
      new VolumeBuilder()
        .withName(mount.name)
        .withNewPersistentVolumeClaim()
        .withClaimName(mount.pvcName.getOrElse(""))
        .endPersistentVolumeClaim()
        .build()
    })

    streamletPvcVolume :+ secretVolume :+ Runner.DownwardApiVolume
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
  private def makeVolumeMountsSpec(streamletToDeploy: Option[App.Streamlet]): Vector[VolumeMount] = {
    val streamletVolumeMount = streamletToDeploy.toVector.flatMap(_.descriptor.volumeMounts.map { mount =>
      new VolumeMountBuilder()
        .withName(mount.name)
        .withMountPath(mount.path)
        .build()
    })

    Vector(
      new VolumeMountBuilder()
        .withName("secret-vol")
        .withMountPath(Runner.SecretMountPath)
        .build(),
      Runner.DownwardApiVolumeMount) ++ streamletVolumeMount
  }
}

object FlinkApp {

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class HostPath(path: String, `type`: String)

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class NamePath(name: String, path: String)

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class NamePathSecretType(name: String, path: String, secretType: String = "Generic")

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class JobManagerConfig(
      replicas: Option[Int],
      resources: Option[ResourceRequirements] = None,
      envConfig: Option[EnvConfig])

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class TaskManagerConfig(
      taskSlots: Option[Int],
      resources: Option[ResourceRequirements] = None,
      envConfig: Option[EnvConfig])

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

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
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
      volumeMounts: Seq[VolumeMount] = Seq.empty,
      restartNonce: String = "")

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class ApplicationState(state: String, errorMessage: Option[String])

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class JobManagerInfo(
      podName: Option[String],
      webUIAddress: Option[String],
      webUIPort: Option[Int],
      webUIServiceName: Option[String])

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class Status(
      appId: Option[String],
      applicationState: Option[ApplicationState],
      completionTime: Option[String],
      jobManagerInfo: Option[JobManagerInfo],
      submissionTime: Option[String] // may need to parse it as a date later on
  )

  final case class EnvConfig(env: Option[Seq[EnvVar]])

  final val GroupName = "flink.k8s.io"
  final val GroupVersion = "v1beta1"
  final val Kind = "FlinkApplication"
  final val Singular = "flinkapplication"
  final val Plural = "flinkapplications"
  final val Scope = "Namespaced"
  final val ApiVersion = GroupName + "/" + GroupVersion
  final val ResourceName = s"$Plural.$GroupName"

  val customResourceDefinitionContext: CustomResourceDefinitionContext =
    new CustomResourceDefinitionContext.Builder()
      .withVersion(GroupVersion)
      .withKind(Kind)
      .withGroup(GroupName)
      .withPlural(Plural)
      .withScope(Scope)
      .build()

  @JsonCreator
  @Group(GroupName)
  @Version(GroupVersion)
  @Kind(Kind)
  @Plural(Plural)
  final case class Cr(
      @JsonProperty("spec")
      spec: Spec,
      @JsonProperty("metadata")
      metadata: ObjectMeta,
      @JsonProperty("status")
      status: Status = null)
      extends CustomResource
      with Namespaced {
    this.setMetadata(metadata)

    def name: String = metadata.getName()
    def namespace: String = metadata.getNamespace()
  }

  @JsonCreator
  class List extends CustomResourceList[Cr] {}

}

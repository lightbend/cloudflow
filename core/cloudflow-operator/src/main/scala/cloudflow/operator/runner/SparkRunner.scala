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
import skuber.api.patch.{ JsonMergePatch, Patch }
import cloudflow.blueprint.deployment._
import SparkResource._

trait PatchProvider[T <: Patch] {
  def patchFormat: Writes[T]
  def patch(
      deployment: StreamletDeployment,
      app: CloudflowApplication.Spec,
      namespace: String,
      updateLabels: Map[String, String]
  )(implicit ctx: DeploymentContext): T
}

/**
 * Creates the ConfigMap and the Runner resource (a SparkResource.CR) that define a Spark [[Runner]].
 */
object SparkRunner extends Runner[CR] with PatchProvider[SpecPatch] {

  def format = implicitly[Format[CR]]
  def patchFormat: Format[SpecPatch] = implicitly[Format[SpecPatch]]
  def editor = new ObjectEditor[CR] {
    override def updateMetadata(obj: CR, newMetadata: ObjectMeta) = obj.copy(metadata = newMetadata)
  }
  def configEditor = new ObjectEditor[ConfigMap] {
    override def updateMetadata(obj: ConfigMap, newMetadata: ObjectMeta) = obj.copy(metadata = newMetadata)
  }

  def resourceDefinition = implicitly[ResourceDefinition[CR]]
  val runtime = "spark"

  def resource(
      deployment: StreamletDeployment,
      app: CloudflowApplication.Spec,
      namespace: String,
      updateLabels: Map[String, String] = Map()
  )(implicit ctx: DeploymentContext): CR = {
    val _spec = patch(deployment, app, namespace, updateLabels)
    val name = Name.ofSparkApplication(deployment.name)
    CustomResource[Spec, Status](_spec.spec)
      .withMetadata(ObjectMeta(name = name, namespace = namespace))
  }

  def patch(
      deployment: StreamletDeployment,
      app: CloudflowApplication.Spec,
      namespace: String,
      updateLabels: Map[String, String] = Map()
  )(implicit ctx: DeploymentContext): SpecPatch = {
    val appLabels = CloudflowLabels(app)
    val appId = app.appId
    val agentPaths = app.agentPaths
    val image = deployment.image
    val configMapName = Name.ofConfigMap(deployment.name)
    val configMaps = Seq(NamePath(configMapName, Runner.ConfigMapMountPath))

    val streamletToDeploy = app.streamlets.find(streamlet ⇒ streamlet.name == deployment.streamletName)

    // Streamlet volume mounting
    // Volume mounting
    val pvcName = Name.ofPVCInstance(appId)

    val pvcVolume = Volume("persistent-storage", Volume.PersistentVolumeClaimRef(pvcName))
    val pvcVolumeMount = Volume.Mount(pvcVolume.name, "/mnt/spark/storage")

    val streamletPvcVolume = streamletToDeploy.toSeq.flatMap(_.descriptor.volumeMounts.map { mount ⇒
      Volume(mount.name, Volume.PersistentVolumeClaimRef(mount.pvcName))
    })
    val streamletVolumeMount = streamletToDeploy.toSeq.flatMap(_.descriptor.volumeMounts.map {
      mount ⇒ Volume.Mount(mount.name, mount.path)
    })

    val volumes = streamletPvcVolume :+ pvcVolume :+ Runner.DownwardApiVolume
    val volumeMounts = streamletVolumeMount :+ pvcVolumeMount :+ Runner.DownwardApiVolumeMount

    // This is the group id of the user in the streamlet container,
    // its need to make volumes managed by certain volume plugins writable.
    // If the image used with the container changes, this value most likely
    // have to be updated
    val dockerContainerGroupId = Runner.DockerContainerGroupId
    val securityContext = if (streamletToDeploy.exists(_.descriptor.volumeMounts.exists(_.accessMode == "ReadWriteMany")))
      Some(SparkResource.SecurityContext(fsGroup = Some(dockerContainerGroupId)))
    else
      None

    val alwaysRestartPolicy: RestartPolicy = AlwaysRestartPolicy(
      onFailureRetryInterval = OnFailureRetryIntervalSecs,
      onSubmissionFailureRetryInterval = OnSubmissionFailureRetryIntervalSecs
    )

    val secrets = Seq(NamePathSecretType(deployment.secretName, Runner.SecretMountPath))

    val name = Name.ofSparkApplication(deployment.name)
    val labels = appLabels.withComponent(name, CloudflowLabels.StreamletComponent) + ("version" -> "2.4.0") ++
      updateLabels ++
      Map(Operator.StreamletNameLabel -> deployment.streamletName, Operator.AppIdLabel -> appId)

    import ctx.sparkRunnerSettings._
    val driver = Driver(
      cores = driverSettings.cores.map(_.amount.floatValue),
      memory = driverSettings.memory.map(_.value),
      coreLimit = driverSettings.coreLimit.map(_.value),
      memoryOverhead = driverSettings.memoryOverhead.map(_.value),
      javaOptions = driverSettings.javaOptions,
      labels = labels,
      volumeMounts = volumeMounts,
      secrets = secrets,
      configMaps = configMaps,
      securityContext = securityContext
    )
    val executor = Executor(
      cores = executorSettings.cores.map(_.amount.floatValue),
      memory = executorSettings.memory.map(_.value),
      coreLimit = executorSettings.coreLimit.map(_.value),
      memoryOverhead = executorSettings.memoryOverhead.map(_.value),
      javaOptions = executorSettings.javaOptions,
      instances = deployment.replicas.getOrElse(DefaultNrOfExecutorInstances),
      labels = labels,
      volumeMounts = volumeMounts,
      secrets = secrets,
      configMaps = configMaps,
      securityContext = securityContext
    )
    val monitoring = Monitoring(prometheus = Prometheus(
      jmxExporterJar = agentPaths(CloudflowApplication.PrometheusAgentKey),
      configFile = PrometheusConfig.prometheusConfigPath(Runner.ConfigMapMountPath)
    ))

    val spec = Spec(
      image = image,
      mainClass = RuntimeMainClass,
      volumes = volumes,
      driver = driver,
      executor = executor,
      restartPolicy = alwaysRestartPolicy,
      monitoring = monitoring
    )
    SpecPatch(spec)
  }

  val DefaultNrOfExecutorInstances = 2

  // Lifecycle Management
  private val OnFailureRetryIntervalSecs = 10
  private val OnSubmissionFailureRetryIntervalSecs = 60

}

object SparkResource {
  private val SparkServiceAccount = Name.ofServiceAccount()

  final case class SecurityContext(fsGroup: Option[Int])

  final case class HostPath(path: String, `type`: String)
  final case class NamePath(name: String, path: String)
  final case class NamePathSecretType(name: String, path: String, secretType: String = "Generic")

  trait RestartPolicy {
    def `type`: String
  }

  final case class OnFailureRestartPolicy(
      onFailureRetries: Int,
      onFailureRetryInterval: Int,
      onSubmissionFailureRetries: Int,
      onSubmissionFailureRetryInterval: Int,
      override val `type`: String = "OnFailure"
  ) extends RestartPolicy

  final case class AlwaysRestartPolicy(
      onFailureRetryInterval: Int,
      onSubmissionFailureRetryInterval: Int,
      override val `type`: String = "Always"
  ) extends RestartPolicy

  // This should be a case object but Json support is limited
  final case class NeverRestartPolicy(
      override val `type`: String = "Never"
  ) extends RestartPolicy

  final case class Prometheus(
      jmxExporterJar: String,
      configFile: String,
      port: Int = PrometheusConfig.PrometheusJmxExporterPort
  )

  final case class Monitoring(
      prometheus: Prometheus,
      exposeDriverMetrics: Boolean = true,
      exposeExecutorMetrics: Boolean = true
  )

  final case class Driver(
      cores: Option[Float] = None,
      coreLimit: Option[String] = None,
      memory: Option[String] = None,
      memoryOverhead: Option[String] = None,
      javaOptions: Option[String] = None,
      serviceAccount: Option[String] = Some(SparkServiceAccount),
      labels: Map[String, String] = Map(),
      configMaps: Seq[NamePath] = Seq(),
      secrets: Seq[NamePathSecretType] = Seq(),
      volumeMounts: Seq[Volume.Mount] = Nil,
      securityContext: Option[SecurityContext] = None
  )

  final case class Executor(
      instances: Int,
      cores: Option[Float] = None,
      coreLimit: Option[String] = None,
      memory: Option[String] = None,
      memoryOverhead: Option[String] = None,
      javaOptions: Option[String] = None,
      labels: Map[String, String] = Map(),
      configMaps: Seq[NamePath] = Seq(),
      secrets: Seq[NamePathSecretType] = Seq(),
      volumeMounts: Seq[Volume.Mount] = Nil,
      securityContext: Option[SecurityContext] = None
  )

  final case class Spec(
      `type`: String = "Scala",
      mode: String = "cluster",
      image: String = "", // required parameter
      imagePullPolicy: String = "Always",
      mainClass: String = "", // required parameter
      mainApplicationFile: Option[String] = Some("spark-internal"),
      volumes: Seq[Volume] = Nil,
      driver: Driver,
      executor: Executor,
      restartPolicy: RestartPolicy,
      monitoring: Monitoring
  )

  implicit val volumeMountFmt: Format[Volume.Mount] = skuber.json.format.volMountFormat
  implicit val volumeFmt: Format[Volume] = skuber.json.format.volumeFormat
  implicit val securityContextFmt: Format[SecurityContext] = Json.format[SecurityContext]
  implicit val hostPathFmt: Format[HostPath] = Json.format[HostPath]
  implicit val namePathFmt: Format[NamePath] = Json.format[NamePath]
  implicit val namePathSecretTypeFmt: Format[NamePathSecretType] = Json.format[NamePathSecretType]
  implicit val driverFmt: Format[Driver] = Json.format[Driver]
  implicit val executorFmt: Format[Executor] = Json.format[Executor]
  implicit val prometheusFmt: Format[Prometheus] = Json.format[Prometheus]
  implicit val monitoringFmt: Format[Monitoring] = Json.format[Monitoring]

  implicit val onFailureRestartPolicyReads: Reads[OnFailureRestartPolicy] = Json.reads[OnFailureRestartPolicy]
  implicit val onFailureRestartPolicyWrites: Writes[OnFailureRestartPolicy] = Json.writes[OnFailureRestartPolicy]

  implicit val alwaysRestartPolicyReads: Reads[AlwaysRestartPolicy] = Json.reads[AlwaysRestartPolicy]
  implicit val alwaysRestartPolicyWrites: Writes[AlwaysRestartPolicy] = Json.writes[AlwaysRestartPolicy]

  implicit val neverRestartPolicyReads: Reads[NeverRestartPolicy] = Json.reads[NeverRestartPolicy]
  implicit val neverRestartPolicyWrites: Writes[NeverRestartPolicy] = Json.writes[NeverRestartPolicy]

  object RestartPolicy {

    implicit val reads: Reads[RestartPolicy] =
      __.read[OnFailureRestartPolicy].map(x ⇒ x: RestartPolicy) orElse
        __.read[AlwaysRestartPolicy].map(x ⇒ x: RestartPolicy) orElse
        __.read[NeverRestartPolicy].map(x ⇒ x: RestartPolicy)

    implicit val writes: Writes[RestartPolicy] = {
      case never: NeverRestartPolicy         ⇒ neverRestartPolicyWrites.writes(never)
      case always: AlwaysRestartPolicy       ⇒ alwaysRestartPolicyWrites.writes(always)
      case onFailure: OnFailureRestartPolicy ⇒ onFailureRestartPolicyWrites.writes(onFailure)
    }
  }
  implicit val specFmt: Format[Spec] = Json.format[Spec]

  // --- Status definition

  final case class ApplicationState(state: String, errorMessage: Option[String])
  final case class DriverInfo(
      podName: Option[String],
      webUIAddress: Option[String],
      webUIPort: Option[Int],
      webUIServiceName: Option[String]
  )
  final case class Status(
      appId: Option[String],
      applicationState: ApplicationState,
      completionTime: Option[String],
      driverInfo: DriverInfo,
      submissionTime: Option[String] // may need to parse it as a date later on
  )

  final case class SpecPatch(spec: Spec) extends JsonMergePatch

  type CR = CustomResource[Spec, Status]

  implicit val applicationStateFmt: Format[ApplicationState] = Json.format[ApplicationState]
  implicit val driverInfoFmt: Format[DriverInfo] = Json.format[DriverInfo]
  implicit val statusFmt: Format[Status] = Json.format[Status]
  implicit val specPatchFmt: Format[SpecPatch] = Json.format[SpecPatch]

  implicit val resourceDefinition: ResourceDefinition[CustomResource[Spec, Status]] = ResourceDefinition[CR](
    group = "sparkoperator.k8s.io",
    version = "v1beta2",
    kind = "SparkApplication"
  )
}

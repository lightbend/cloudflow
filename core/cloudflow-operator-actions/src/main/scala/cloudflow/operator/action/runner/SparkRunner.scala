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

import scala.jdk.CollectionConverters._
import SparkResource._
import com.typesafe.config._
import play.api.libs.json._

import skuber.api.patch.{ JsonMergePatch, Patch }
import skuber.json.format._
import skuber.json.rbac.format._
import skuber.rbac._
import skuber._
import skuber.Resource._
import skuber.ResourceSpecification.Subresources

import cloudflow.blueprint.deployment._
import cloudflow.operator._
import cloudflow.operator.action._

trait PatchProvider[T <: Patch] {
  def patchFormat: Writes[T]
  def patch(
      deployment: StreamletDeployment,
      app: CloudflowApplication.CR,
      configSecret: Secret,
      updateLabels: Map[String, String]
  ): T
}

object SparkRunner {
  final val Runtime                = "spark"
  val DefaultNrOfExecutorInstances = 1
}

/**
 * Creates the ConfigMap and the Runner resource (a SparkResource.CR) that define a Spark [[Runner]].
 */
final class SparkRunner(sparkRunnerDefaults: SparkRunnerDefaults) extends Runner[CR] with PatchProvider[SpecPatch] {
  import SparkRunner._
  import sparkRunnerDefaults._
  val runtime                        = Runtime
  def format                         = implicitly[Format[CR]]
  def patchFormat: Format[SpecPatch] = implicitly[Format[SpecPatch]]
  def editor = new ObjectEditor[CR] {
    override def updateMetadata(obj: CR, newMetadata: ObjectMeta) = obj.copy(metadata = newMetadata)
  }
  def configEditor = new ObjectEditor[ConfigMap] {
    override def updateMetadata(obj: ConfigMap, newMetadata: ObjectMeta) = obj.copy(metadata = newMetadata)
  }

  def resourceDefinition = implicitly[ResourceDefinition[CR]]
  def prometheusConfig   = PrometheusConfig(prometheusRules)

  val DriverPod   = "driver"
  val ExecutorPod = "executor"

  def appActions(app: CloudflowApplication.CR, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): Seq[Action] = {
    val roleSpark = sparkRole(app.namespace, labels, ownerReferences)

    Vector(
      Action.createOrUpdate(roleSpark, app, roleEditor),
      Action.createOrUpdate(sparkRoleBinding(app.namespace, roleSpark, labels, ownerReferences), app, roleBindingEditor)
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
    val patch = SpecPatch(labeledResource.spec)
    Action.createOrPatch(_resource, app, patch)(format, patchFormat, resourceDefinition)
  }

  override def updateActions(newApp: CloudflowApplication.CR,
                             runners: Map[String, Runner[_]],
                             deployment: StreamletDeployment): Seq[ResourceAction[ObjectResource]] = {
    val patchAction = Action.provided[Secret, ObjectResource](deployment.secretName, newApp) {
      case Some(secret) =>
        val _resource = resource(deployment, newApp, secret)
        val _patch    = patch(deployment, newApp, secret)
        Action.patch(_resource, newApp, _patch)(format, patchFormat, resourceDefinition)
      case None =>
        val msg = s"Secret ${deployment.secretName} is missing for streamlet deployment '${deployment.name}'."
        log.error(msg)
        CloudflowApplication.Status.errorAction(newApp, runners, msg)
    }

    val configAction = Action.createOrUpdate(configResource(deployment, newApp), newApp, configEditor)
    Seq(configAction, patchAction)
  }

  def defaultReplicas                                   = DefaultNrOfExecutorInstances
  def expectedPodCount(deployment: StreamletDeployment) = deployment.replicas.getOrElse(SparkRunner.DefaultNrOfExecutorInstances) + 1
  private def sparkRole(namespace: String, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): Role =
    Role(
      metadata = ObjectMeta(
        name = Name.ofSparkRole,
        namespace = namespace,
        labels = labels(Name.ofSparkRole),
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

  private def sparkRoleBinding(namespace: String, role: Role, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): RoleBinding =
    RoleBinding(
      metadata = ObjectMeta(
        name = Name.ofSparkRoleBinding,
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
    val ownerReferences = List(OwnerReference(app.apiVersion, app.kind, app.metadata.name, app.metadata.uid, Some(true), Some(true)))
    val _spec           = patch(deployment, app, configSecret, updateLabels)
    val name            = resourceName(deployment)
    CustomResource[Spec, Status](_spec.spec)
      .withMetadata(ObjectMeta(name = name, namespace = app.namespace, ownerReferences = ownerReferences))
  }

  def resourceName(deployment: StreamletDeployment): String = Name.ofSparkApplication(deployment.name)

  def patch(
      deployment: StreamletDeployment,
      app: CloudflowApplication.CR,
      configSecret: Secret,
      updateLabels: Map[String, String] = Map()
  ): SpecPatch = {
    val podsConfig = getPodsConfig(configSecret)

    val appLabels     = CloudflowLabels(app)
    val appId         = app.spec.appId
    val agentPaths    = app.spec.agentPaths
    val image         = deployment.image
    val configMapName = Name.ofConfigMap(deployment.name)
    val configMaps    = Seq(NamePath(configMapName, Runner.ConfigMapMountPath))

    val streamletToDeploy = app.spec.streamlets.find(streamlet => streamlet.name == deployment.streamletName)

    // Streamlet volume mounting (Defined by Streamlet.volumeMounts API)
    val streamletPvcVolume = streamletToDeploy.toSeq.flatMap(_.descriptor.volumeMounts.map { mount =>
      Volume(mount.name, Volume.PersistentVolumeClaimRef(mount.pvcName))
    })
    val streamletVolumeMount = streamletToDeploy.toSeq.flatMap(_.descriptor.volumeMounts.map { mount =>
      Volume.Mount(mount.name, mount.path)
    })

    val volumes      = streamletPvcVolume ++ getVolumes(podsConfig, PodsConfig.CloudflowPodName) :+ Runner.DownwardApiVolume
    val volumeMounts = streamletVolumeMount :+ Runner.DownwardApiVolumeMount

    // This is the group id of the user in the streamlet container,
    // its need to make volumes managed by certain volume plugins writable.
    // If the image used with the container changes, this value most likely
    // have to be updated
    val dockerContainerGroupId = Runner.DockerContainerGroupId
    val securityContext =
      if (streamletToDeploy.exists(_.descriptor.volumeMounts.exists(_.accessMode == "ReadWriteMany")))
        Some(SparkResource.SecurityContext(fsGroup = Some(dockerContainerGroupId)))
      else
        None

    val alwaysRestartPolicy: RestartPolicy = AlwaysRestartPolicy(
      onFailureRetryInterval = OnFailureRetryIntervalSecs,
      onSubmissionFailureRetryInterval = OnSubmissionFailureRetryIntervalSecs
    )

    val secrets = Seq(NamePathSecretType(deployment.secretName, Runner.SecretMountPath))

    val name = resourceName(deployment)
    val labels = appLabels.withComponent(name, CloudflowLabels.StreamletComponent) + ("version" -> "2.4.5") ++
          updateLabels ++
          Map(CloudflowLabels.StreamletNameLabel -> deployment.streamletName, CloudflowLabels.AppIdLabel -> appId).view
            .mapValues(Name.ofLabelValue)

    val driver = addDriverResourceRequirements(
      Driver(
        javaOptions = getJavaOptions(podsConfig, DriverPod).orElse(driverDefaults.javaOptions),
        labels = labels ++ getLabels(podsConfig, DriverPod),
        volumeMounts = volumeMounts ++ getVolumeMounts(podsConfig, DriverPod),
        secrets = secrets,
        env = getEnvironmentVariables(podsConfig, DriverPod),
        configMaps = configMaps,
        securityContext = securityContext
      ),
      podsConfig,
      deployment
    )
    val executor = addExecutorResourceRequirements(
      Executor(
        javaOptions = getJavaOptions(podsConfig, ExecutorPod).orElse(executorDefaults.javaOptions),
        instances = deployment.replicas.getOrElse(DefaultNrOfExecutorInstances),
        labels = labels ++ getLabels(podsConfig, ExecutorPod),
        volumeMounts = volumeMounts ++ getVolumeMounts(podsConfig, ExecutorPod),
        secrets = secrets,
        env = getEnvironmentVariables(podsConfig, ExecutorPod),
        configMaps = configMaps,
        securityContext = securityContext
      ),
      podsConfig,
      deployment
    )

    val monitoring = {
      if (!agentPaths.contains(CloudflowApplication.PrometheusAgentKey)) {
        Monitoring(prometheus = Prometheus(
          jmxExporterJar = "/prometheus/jmx_prometheus_javaagent.jar",
          configFile = "/etc/cloudflow-runner/prometheus.yaml",
          port = 2050
        )
        )
      } else {
        Monitoring(prometheus = Prometheus(jmxExporterJar = agentPaths(CloudflowApplication.PrometheusAgentKey),
                                           configFile = PrometheusConfig.prometheusConfigPath(Runner.ConfigMapMountPath))
        )
      }
    }

    val defaultDriverCores            = toIntCores(driverDefaults.cores)
    val defaultDriverMemory           = driverDefaults.memory.map(_.value)
    val defaultDriverMemoryOverhead   = driverDefaults.memoryOverhead.map(_.value)
    val defaultExecutorCores          = toIntCores(executorDefaults.cores)
    val defaultExecutorMemory         = executorDefaults.memory.map(_.value)
    val defaultExecutorMemoryOverhead = executorDefaults.memoryOverhead.map(_.value)

    val sparkConf = getSparkConf(
      configSecret,
      defaultDriverCores,
      defaultDriverMemory,
      defaultDriverMemoryOverhead,
      defaultExecutorCores,
      defaultExecutorMemory,
      defaultExecutorMemoryOverhead
    )
    val spec = Spec(
      image = image,
      mainClass = RuntimeMainClass,
      sparkConf = sparkConf,
      volumes = volumes,
      driver = driver,
      executor = executor,
      restartPolicy = alwaysRestartPolicy,
      monitoring = monitoring
    )
    SpecPatch(spec)
  }

  // Lifecycle Management
  private val OnFailureRetryIntervalSecs           = 10
  private val OnSubmissionFailureRetryIntervalSecs = 60

  private def addDriverResourceRequirements(driver: Driver, podsConfig: PodsConfig, deployment: StreamletDeployment): Driver = {
    var updatedDriver = driver

    updatedDriver = updatedDriver.copy(
      coreLimit = driverDefaults.coreLimit.map(_.value)
    )
    // you can set the "driver" pod or just "pod", which means it will be used for both driver and executor (as fallback).
    updatedDriver = podsConfig.pods
      .get(DriverPod)
      .orElse(podsConfig.pods.get(PodsConfig.CloudflowPodName))
      .flatMap { podConfig =>
        podConfig.containers.get(PodsConfig.CloudflowContainerName).flatMap { containerConfig =>
          containerConfig.resources.map { resources =>
            updatedDriver.copy(
              coreLimit = resources.limits.get(Resource.cpu).map(_.toString).orElse(updatedDriver.coreLimit)
            )
          }
        }
      }
      .getOrElse(updatedDriver)
    log.debug(s"""
    Streamlet ${deployment.streamletName} - resources for driver pod:
      coreLimit:      ${updatedDriver.coreLimit}
    """)
    updatedDriver
  }

  private def addExecutorResourceRequirements(executor: Executor, podsConfig: PodsConfig, deployment: StreamletDeployment): Executor = {
    var updatedExecutor = executor

    updatedExecutor = updatedExecutor.copy(
      coreLimit = executorDefaults.coreLimit.map(_.value)
    )
    // you can set the "executor" pod or just "pod", which means it will be used for both executor and executor (as fallback).
    updatedExecutor = podsConfig.pods
      .get(ExecutorPod)
      .orElse(podsConfig.pods.get(PodsConfig.CloudflowPodName))
      .flatMap { podConfig =>
        podConfig.containers.get(PodsConfig.CloudflowContainerName).flatMap { containerConfig =>
          containerConfig.resources.map { resources =>
            updatedExecutor.copy(
              coreRequest = resources.requests.get(Resource.cpu).map(_.value),
              coreLimit = resources.limits.get(Resource.cpu).map(_.toString).orElse(updatedExecutor.coreLimit)
            )
          }
        }
      }
      .getOrElse(updatedExecutor)

    log.debug(s"""
    Streamlet ${deployment.streamletName} - resources for executor pod:
      coreRequest:    ${updatedExecutor.coreRequest}
      coreLimit:      ${updatedExecutor.coreLimit}
    """)
    updatedExecutor
  }

  private def toIntCores(cores: Option[Quantity]): Option[Int] =
    cores
      .map { quantity =>
        val coresInt = quantity.amount.intValue
        if (coresInt >= 1) coresInt else 1
      }
      .orElse(Some(1))

  private def getSparkConf(configSecret: Secret,
                           defaultDriverCores: Option[Int],
                           defaultDriverMemory: Option[String],
                           defaultDriverMemoryOverhead: Option[String],
                           defaultExecutorCores: Option[Int],
                           defaultExecutorMemory: Option[String],
                           defaultExecutorMemoryOverhead: Option[String]): Option[Map[String, String]] = {
    val defaultConfigMap = List(
      defaultDriverCores.map(v => "spark.driver.cores"                       -> v),
      defaultDriverMemory.map(v => "spark.driver.memory"                     -> v),
      defaultDriverMemoryOverhead.map(v => "spark.driver.memoryOverhead"     -> v),
      defaultExecutorCores.map(v => "spark.executor.cores"                   -> v),
      defaultExecutorMemory.map(v => "spark.executor.memory"                 -> v),
      defaultExecutorMemoryOverhead.map(v => "spark.executor.memoryOverhead" -> v)
    ).flatten.toMap
    val defaultConfig = defaultConfigMap.foldLeft(ConfigFactory.empty) {
      case (acc, (path, value)) =>
        acc.withValue(path, ConfigValueFactory.fromAnyRef(value))
    }
    val conf = getRuntimeConfig(configSecret).withFallback(defaultConfig)
    if (conf.isEmpty) None
    else {
      val sparkConfMap = Some(
        conf
          .entrySet()
          .asScala
          .map(entry => entry.getKey -> entry.getValue.unwrapped().toString)
          .toMap
      )
      log.debug(s"Setting SparkConf from secret ${configSecret.metadata.namespace}/${configSecret.metadata.name}: $sparkConfMap")
      sparkConfMap
    }
  }
}

object SparkResource {
  private val SparkServiceAccount = Name.ofServiceAccount

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

  /**
   * NOTE: coreRequest in Driver is only supported in Spark 3.0:
   * https://github.com/GoogleCloudPlatform/spark-on-k8s-operator/blob/8c480acfdd09882ed2f00573f15e7830558de524/pkg/apis/sparkoperator.k8s.io/v1beta2/types.go#L499
   */
  final case class Driver(
      coreLimit: Option[String] = None,
      env: Option[List[EnvVar]] = None,
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
      coreRequest: Option[String] = None,
      coreLimit: Option[String] = None,
      env: Option[List[EnvVar]] = None,
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
      sparkVersion: String = "2.4.5",
      image: String = "", // required parameter
      imagePullPolicy: String = "Always",
      mainClass: String = "", // required parameter
      sparkConf: Option[Map[String, String]] = None,
      mainApplicationFile: Option[String] = Some("spark-internal"),
      volumes: Seq[Volume] = Nil,
      driver: Driver,
      executor: Executor,
      restartPolicy: RestartPolicy,
      monitoring: Monitoring
  )

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

  implicit val securityContextFmt: Format[SecurityContext]       = Json.format[SecurityContext]
  implicit val hostPathFmt: Format[HostPath]                     = Json.format[HostPath]
  implicit val namePathFmt: Format[NamePath]                     = Json.format[NamePath]
  implicit val namePathSecretTypeFmt: Format[NamePathSecretType] = Json.format[NamePathSecretType]
  implicit val driverFmt: Format[Driver]                         = Json.format[Driver]
  implicit val executorFmt: Format[Executor]                     = Json.format[Executor]
  implicit val prometheusFmt: Format[Prometheus]                 = Json.format[Prometheus]
  implicit val monitoringFmt: Format[Monitoring]                 = Json.format[Monitoring]

  implicit val onFailureRestartPolicyReads: Reads[OnFailureRestartPolicy]   = Json.reads[OnFailureRestartPolicy]
  implicit val onFailureRestartPolicyWrites: Writes[OnFailureRestartPolicy] = Json.writes[OnFailureRestartPolicy]

  implicit val alwaysRestartPolicyReads: Reads[AlwaysRestartPolicy]   = Json.reads[AlwaysRestartPolicy]
  implicit val alwaysRestartPolicyWrites: Writes[AlwaysRestartPolicy] = Json.writes[AlwaysRestartPolicy]

  implicit val neverRestartPolicyReads: Reads[NeverRestartPolicy]   = Json.reads[NeverRestartPolicy]
  implicit val neverRestartPolicyWrites: Writes[NeverRestartPolicy] = Json.writes[NeverRestartPolicy]

  object RestartPolicy {

    implicit val reads: Reads[RestartPolicy] =
      __.read[OnFailureRestartPolicy]
        .map(x => x: RestartPolicy)
        .orElse(__.read[AlwaysRestartPolicy].map(x => x: RestartPolicy))
        .orElse(__.read[NeverRestartPolicy].map(x => x: RestartPolicy))

    implicit val writes: Writes[RestartPolicy] = {
      case never: NeverRestartPolicy         => neverRestartPolicyWrites.writes(never)
      case always: AlwaysRestartPolicy       => alwaysRestartPolicyWrites.writes(always)
      case onFailure: OnFailureRestartPolicy => onFailureRestartPolicyWrites.writes(onFailure)
    }
  }

  implicit val driverInfoFmt: Format[DriverInfo]             = Json.format[DriverInfo]
  implicit val applicationStateFmt: Format[ApplicationState] = Json.format[ApplicationState]
  implicit val specFmt: Format[Spec]                         = Json.format[Spec]
  implicit val statusFmt: Format[Status]                     = Json.format[Status]

  final case class SpecPatch(spec: Spec) extends JsonMergePatch

  type CR = CustomResource[Spec, Status]

  implicit val specPatchFmt: Format[SpecPatch] = Json.format[SpecPatch]

  implicit val resourceDefinition: ResourceDefinition[CustomResource[Spec, Status]] = ResourceDefinition[CR](
    group = "sparkoperator.k8s.io",
    version = "v1beta2",
    kind = "SparkApplication",
    subresources = Some(Subresources().withStatusSubresource())
  )

  implicit val statusSubEnabled = CustomResource.statusMethodsEnabler[CR]
}

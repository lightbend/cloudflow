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
import io.fabric8.kubernetes.api.model.rbac._
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext
import io.fabric8.kubernetes.client.{ CustomResource, CustomResourceList }
import io.fabric8.kubernetes.model.annotation.{ Group, Kind, Plural, Version }

import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag

object SparkRunner {
  final val Runtime = "spark"
  val DefaultNrOfExecutorInstances = 1
}

/**
 * Creates the ConfigMap and the Runner resource (a SparkResource.CR) that define a Spark [[Runner]].
 */
final class SparkRunner(sparkRunnerDefaults: SparkRunnerDefaults) extends Runner[SparkApp.Cr] {
  import SparkRunner._
  import sparkRunnerDefaults._
  val runtime = Runtime

  implicit val adapter =
    CustomResourceAdapter[SparkApp.Cr, SparkApp.List](SparkApp.customResourceDefinitionContext)

  def prometheusConfig = PrometheusConfig(prometheusRules)

  val DriverPod = "driver"
  val ExecutorPod = "executor"

  def appActions(app: App.Cr, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): Seq[Action] = {
    val roleSpark = sparkRole(app.namespace, labels, ownerReferences)

    Seq(
      Action.createOrReplace(roleSpark),
      Action.createOrReplace(sparkRoleBinding(app.namespace, roleSpark, labels, ownerReferences)))
  }

  def streamletChangeAction(
      app: App.Cr,
      runners: Map[String, Runner[_]],
      streamletDeployment: App.Deployment,
      secret: Secret) = {
    val updateLabels = Map(CloudflowLabels.ConfigUpdateLabel -> System.currentTimeMillis.toString)

    val res = resource(streamletDeployment, app, secret, updateLabels)
    val metadata = res.getMetadata
    val labels = metadata.getLabels

    val newLabels = {
      if (labels != null) (labels.asScala ++ updateLabels)
      else updateLabels
    }

    metadata.setLabels(newLabels.asJava)
    res.setMetadata(metadata)

    Action.Cr.get[SparkApp.Cr](res.name, res.namespace) { current =>
      current match {
        case Some(curr) if (curr.spec != res.spec) =>
          Action.Cr.createOrReplace(res)
        case _ =>
          Action.noop
      }
    }
  }

  override def updateActions(newApp: App.Cr, runners: Map[String, Runner[_]], deployment: App.Deployment)(
      implicit ct: ClassTag[SparkApp.Cr]): Seq[Action] = {
    val patchAction = Action.get[Secret](deployment.secretName, newApp.namespace) {
      case Some(secret) =>
        val res: SparkApp.Cr = resource(deployment, newApp, secret)
        val spec = getSpec(deployment, newApp, secret)

        // TODO: check if this works as expected or we really need to patch
        // TODO: very likely this needs to be a real patch!
        Action.Cr.get[SparkApp.Cr](res.name, res.namespace) { current =>
          current match {
            case Some(curr) if (curr.spec != spec) =>
              Action.Cr.createOrReplace[SparkApp.Cr](res.copy(spec = spec))
            case _ =>
              Action.noop
          }
        }
      case None =>
        val msg = s"Secret ${deployment.secretName} is missing for streamlet deployment '${deployment.name}'."
        log.error(msg)
        CloudflowStatus.errorAction(newApp, runners, msg)
    }

    val configAction =
      Action.createOrReplace(configResource(deployment, newApp))

    Seq(configAction, patchAction)
  }

  def defaultReplicas = DefaultNrOfExecutorInstances
  def expectedPodCount(deployment: App.Deployment) =
    deployment.replicas.getOrElse(SparkRunner.DefaultNrOfExecutorInstances) + 1
  private def sparkRole(namespace: String, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): Role = {
    new RoleBuilder()
      .withNewMetadata()
      .withName(Name.ofSparkRole)
      .withNamespace(namespace)
      .withLabels(labels(Name.ofSparkRole).asJava)
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

  private def sparkRoleBinding(
      namespace: String,
      role: Role,
      labels: CloudflowLabels,
      ownerReferences: List[OwnerReference]): RoleBinding = {
    new RoleBindingBuilder()
      .withNewMetadata()
      .withName(Name.ofSparkRoleBinding)
      .withNamespace(namespace)
      .withLabels(labels(Name.ofRoleBinding).asJava)
      .withOwnerReferences(ownerReferences: _*)
      .endMetadata()
      .withKind("RoleBinding")
      .withNewRoleRef()
      .withApiGroup("rbac.authorization.k8s.io")
      .withKind("Role")
      .withName(role.getMetadata.getName)
      .endRoleRef()
      .withSubjects(new SubjectBuilder()
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
      updateLabels: Map[String, String] = Map()): SparkApp.Cr = {
    val ownerReferences = List(
      // TODO: repeated again and again
      new OwnerReferenceBuilder()
        .withApiVersion(app.getApiVersion)
        .withKind(app.getKind)
        .withName(app.getMetadata.getName)
        .withUid(app.getMetadata.getUid)
        .withController(true)
        .withBlockOwnerDeletion(true)
        .build())
    val spec = getSpec(deployment, app, configSecret, updateLabels)
    val name = resourceName(deployment)

    SparkApp.Cr(
      metadata = new ObjectMetaBuilder()
        .withName(name)
        .withNamespace(app.namespace)
        .withOwnerReferences(ownerReferences: _*)
        .build(),
      spec = spec)
  }

  def resourceName(deployment: App.Deployment): String = Name.ofSparkApplication(deployment.name)

  override def deleteResource(name: String, namespace: String)(implicit ct: ClassTag[SparkApp.Cr]): Action =
    Action.Cr.delete(name, namespace)

  override def createOrReplaceResource(res: SparkApp.Cr)(implicit ct: ClassTag[SparkApp.Cr]): Action = {
    Action.Cr.createOrReplace(res)
  }

  def getSpec(
      deployment: App.Deployment,
      app: App.Cr,
      configSecret: Secret,
      updateLabels: Map[String, String] = Map()): SparkApp.Spec = {
    val podsConfig = getPodsConfig(configSecret)

    val appLabels = CloudflowLabels(app)
    val appId = app.spec.appId
    val agentPaths = app.spec.agentPaths
    val image = deployment.image
    val configMapName = Name.ofConfigMap(deployment.name)
    val configMaps = Seq(SparkApp.NamePath(configMapName, Runner.ConfigMapMountPath))

    val streamletToDeploy = app.spec.streamlets.find(streamlet => streamlet.name == deployment.streamletName)

    // Streamlet volume mounting (Defined by Streamlet.volumeMounts API)
    val streamletPvcVolume = streamletToDeploy.toSeq.flatMap(_.descriptor.volumeMounts.map { mount =>
      new VolumeBuilder()
        .withName(mount.appId)
        .withNewPersistentVolumeClaim()
        .withClaimName(mount.pvcName.getOrElse(""))
        .endPersistentVolumeClaim()
        .build()
    })
    val streamletVolumeMount = streamletToDeploy.toSeq.flatMap(_.descriptor.volumeMounts.map { mount =>
      new VolumeMountBuilder()
        .withName(mount.appId)
        .withMountPath(mount.path)
        .build()
    })

    val volumes = streamletPvcVolume ++ getVolumes(podsConfig, PodsConfig.CloudflowPodName) :+ Runner.DownwardApiVolume
    val volumeMounts = streamletVolumeMount :+ Runner.DownwardApiVolumeMount

    // This is the group id of the user in the streamlet container,
    // its need to make volumes managed by certain volume plugins writable.
    // If the image used with the container changes, this value most likely
    // have to be updated
    val dockerContainerGroupId = Runner.DockerContainerGroupId
    val securityContext =
      if (streamletToDeploy.exists(_.descriptor.volumeMounts.exists(_.accessMode == "ReadWriteMany")))
        Some(SparkApp.SecurityContext(fsGroup = Some(dockerContainerGroupId)))
      else
        None

    val alwaysRestartPolicy: SparkApp.RestartPolicy = SparkApp.RestartPolicy(
      onFailureRetryInterval = Some(OnFailureRetryIntervalSecs),
      onSubmissionFailureRetryInterval = Some(OnSubmissionFailureRetryIntervalSecs),
      `type` = "Always")

    val secrets = Seq(SparkApp.NamePathSecretType(deployment.secretName, Runner.SecretMountPath))

    val name = resourceName(deployment)
    val labels = appLabels.withComponent(name, CloudflowLabels.StreamletComponent) + ("version" -> "2.4.5") ++
      updateLabels ++
      Map(CloudflowLabels.StreamletNameLabel -> deployment.streamletName, CloudflowLabels.AppIdLabel -> appId).view
        .mapValues(Name.ofLabelValue)

    val driver = addDriverResourceRequirements(
      SparkApp.Driver(
        javaOptions = getJavaOptions(podsConfig, DriverPod).orElse(driverDefaults.javaOptions),
        labels = labels ++ getLabels(podsConfig, DriverPod),
        annotations = getAnnotations(podsConfig, DriverPod),
        volumeMounts = volumeMounts ++ getVolumeMounts(podsConfig, DriverPod),
        secrets = secrets,
        env = getEnvironmentVariables(podsConfig, DriverPod),
        configMaps = configMaps,
        securityContext = securityContext),
      podsConfig,
      deployment)
    val executor = addExecutorResourceRequirements(
      SparkApp.Executor(
        javaOptions = getJavaOptions(podsConfig, ExecutorPod).orElse(executorDefaults.javaOptions),
        instances = deployment.replicas.getOrElse(DefaultNrOfExecutorInstances),
        labels = labels ++ getLabels(podsConfig, ExecutorPod),
        annotations = getAnnotations(podsConfig, ExecutorPod),
        volumeMounts = volumeMounts ++ getVolumeMounts(podsConfig, ExecutorPod),
        secrets = secrets,
        env = getEnvironmentVariables(podsConfig, ExecutorPod),
        configMaps = configMaps,
        securityContext = securityContext),
      podsConfig,
      deployment)

    val monitoring = {
      if (!agentPaths.contains(Util.PrometheusAgentKey)) {
        SparkApp.Monitoring(prometheus = SparkApp.Prometheus(
          jmxExporterJar = "/prometheus/jmx_prometheus_javaagent.jar",
          configFile = "/etc/cloudflow-runner/prometheus.yaml",
          port = 2050))
      } else {
        SparkApp.Monitoring(prometheus = SparkApp.Prometheus(
          jmxExporterJar = agentPaths(Util.PrometheusAgentKey),
          configFile = PrometheusConfig.prometheusConfigPath(Runner.ConfigMapMountPath)))
      }
    }

    // TODO all this going from quantity to string and back is starting to make me feel uncomfortable
    val defaultDriverCores = toIntCores(driverDefaults.cores)
    val defaultDriverMemory = driverDefaults.memory.map(_.toString)
    val defaultDriverMemoryOverhead = driverDefaults.memoryOverhead.map(_.toString)
    val defaultExecutorCores = toIntCores(executorDefaults.cores)
    val defaultExecutorMemory = executorDefaults.memory.map(_.toString)
    val defaultExecutorMemoryOverhead = executorDefaults.memoryOverhead.map(_.toString)

    val sparkConf = getSparkConf(
      configSecret,
      defaultDriverCores,
      defaultDriverMemory,
      defaultDriverMemoryOverhead,
      defaultExecutorCores,
      defaultExecutorMemory,
      defaultExecutorMemoryOverhead)
    val spec = SparkApp.Spec(
      image = image,
      mainClass = RuntimeMainClass,
      sparkConf = sparkConf,
      volumes = volumes,
      driver = driver,
      executor = executor,
      restartPolicy = alwaysRestartPolicy,
      monitoring = monitoring)

    spec
  }

  // Lifecycle Management
  private val OnFailureRetryIntervalSecs = 10
  private val OnSubmissionFailureRetryIntervalSecs = 60

  private def addDriverResourceRequirements(
      driver: SparkApp.Driver,
      podsConfig: PodsConfig,
      deployment: App.Deployment): SparkApp.Driver = {
    var updatedDriver = driver

    updatedDriver = updatedDriver.copy(coreLimit = driverDefaults.coreLimit.map(_.toString))
    // you can set the "driver" pod or just "pod", which means it will be used for both driver and executor (as fallback).
    updatedDriver = podsConfig.pods
      .get(DriverPod)
      .orElse(podsConfig.pods.get(PodsConfig.CloudflowPodName))
      .flatMap { podConfig =>
        podConfig.containers.get(PodsConfig.CloudflowContainerName).flatMap { containerConfig =>
          containerConfig.resources.map { resources =>
            val newLimit: Option[String] =
              resources.getLimits.asScala
                .get("cpu")
                .map(_.toString)
                .orElse(updatedDriver.coreLimit)
            updatedDriver.copy(coreLimit = newLimit)
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

  private def addExecutorResourceRequirements(
      executor: SparkApp.Executor,
      podsConfig: PodsConfig,
      deployment: App.Deployment): SparkApp.Executor = {
    var updatedExecutor = executor

    updatedExecutor = updatedExecutor.copy(coreLimit = executorDefaults.coreLimit.map(_.toString))
    // you can set the "executor" pod or just "pod", which means it will be used for both executor and executor (as fallback).
    updatedExecutor = podsConfig.pods
      .get(ExecutorPod)
      .orElse(podsConfig.pods.get(PodsConfig.CloudflowPodName))
      .flatMap { podConfig =>
        podConfig.containers.get(PodsConfig.CloudflowContainerName).flatMap { containerConfig =>
          containerConfig.resources.map { resources =>
            updatedExecutor.copy(
              coreRequest = resources.getRequests.asScala.get("cpu").map(_.toString),
              coreLimit = resources.getLimits.asScala.get("cpu").map(_.toString).orElse(updatedExecutor.coreLimit))
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

  // TODO: changed the original logic that seem to be incorrect
  private def toIntCores(cores: Option[Quantity]): Option[Int] =
    cores
      .map { quantity =>
        val coresInt = Quantity.getAmountInBytes(quantity).intValue()
        if (coresInt >= 1) coresInt else 1
      }
      .orElse(Some(1))

  private def getSparkConf(
      configSecret: Secret,
      defaultDriverCores: Option[Int],
      defaultDriverMemory: Option[String],
      defaultDriverMemoryOverhead: Option[String],
      defaultExecutorCores: Option[Int],
      defaultExecutorMemory: Option[String],
      defaultExecutorMemoryOverhead: Option[String]): Option[Map[String, String]] = {
    val defaultConfigMap = List(
      defaultDriverCores.map(v => "spark.driver.cores" -> v),
      defaultDriverMemory.map(v => "spark.driver.memory" -> v),
      defaultDriverMemoryOverhead.map(v => "spark.driver.memoryOverhead" -> v),
      defaultExecutorCores.map(v => "spark.executor.cores" -> v),
      defaultExecutorMemory.map(v => "spark.executor.memory" -> v),
      defaultExecutorMemoryOverhead.map(v => "spark.executor.memoryOverhead" -> v)).flatten.toMap
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
          .toMap)
      log.debug(
        s"Setting SparkConf from secret ${Option(configSecret.getMetadata).map(_.getNamespace).getOrElse("unknown")}/${Option(
          configSecret.getMetadata).map(_.getName).getOrElse("unknown")}: $sparkConfMap")
      sparkConfMap
    }
  }
}

object SparkApp {
  private val SparkServiceAccount = Name.ofServiceAccount

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class SecurityContext(fsGroup: Option[Int]) extends KubernetesResource {}

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class HostPath(path: String, `type`: String) extends KubernetesResource {}

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class NamePath(name: String, path: String) extends KubernetesResource {}

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class NamePathSecretType(name: String, path: String, secretType: String = "Generic")
      extends KubernetesResource {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class RestartPolicy(
      onFailureRetries: Option[Int] = None,
      onFailureRetryInterval: Option[Int] = None,
      onSubmissionFailureRetries: Option[Int] = None,
      onSubmissionFailureRetryInterval: Option[Int] = None,
      `type`: String)
      extends KubernetesResource {}

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class Prometheus(
      jmxExporterJar: String,
      configFile: String,
      port: Int = PrometheusConfig.PrometheusJmxExporterPort)
      extends KubernetesResource {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class Monitoring(
      prometheus: Prometheus,
      exposeDriverMetrics: Boolean = true,
      exposeExecutorMetrics: Boolean = true)
      extends KubernetesResource {}

  /**
   * NOTE: coreRequest in Driver is only supported in Spark 3.0:
   * https://github.com/GoogleCloudPlatform/spark-on-k8s-operator/blob/8c480acfdd09882ed2f00573f15e7830558de524/pkg/apis/sparkoperator.k8s.io/v1beta2/types.go#L499
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class Driver(
      coreLimit: Option[String] = None,
      env: Option[Seq[EnvVar]] = None,
      javaOptions: Option[String] = None,
      serviceAccount: Option[String] = Some(SparkServiceAccount),
      labels: Map[String, String] = Map(),
      annotations: Map[String, String] = Map(),
      configMaps: Seq[NamePath] = Seq(),
      secrets: Seq[NamePathSecretType] = Seq(),
      volumeMounts: Seq[VolumeMount] = Nil,
      securityContext: Option[SecurityContext] = None)
      extends KubernetesResource {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class Executor(
      instances: Int,
      coreRequest: Option[String] = None,
      coreLimit: Option[String] = None,
      env: Option[Seq[EnvVar]] = None,
      javaOptions: Option[String] = None,
      labels: Map[String, String] = Map(),
      annotations: Map[String, String] = Map(),
      configMaps: Seq[NamePath] = Seq(),
      secrets: Seq[NamePathSecretType] = Seq(),
      volumeMounts: Seq[VolumeMount] = Nil,
      securityContext: Option[SecurityContext] = None)
      extends KubernetesResource {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
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
      monitoring: Monitoring)
      extends KubernetesResource {}

  // --- Status definition
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class ApplicationState(state: String, errorMessage: Option[String]) extends KubernetesResource {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class DriverInfo(
      podName: Option[String],
      webUIAddress: Option[String],
      webUIPort: Option[Int],
      webUIServiceName: Option[String])
      extends KubernetesResource {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class Status(
      appId: Option[String],
      applicationState: ApplicationState,
      completionTime: Option[String],
      driverInfo: DriverInfo,
      submissionTime: Option[String] // may need to parse it as a date later on
  ) extends KubernetesResource {}

  final val GroupName = "sparkoperator.k8s.io"
  final val GroupVersion = "v1beta2"
  final val Kind = "SparkApplication"
  final val Singular = "sparkapplication"
  final val Plural = "sparkapplications"
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
      extends CustomResource[Spec, Status]
      with Namespaced {
    this.setMetadata(metadata)

    def name: String = metadata.getName()
    def namespace: String = metadata.getNamespace()
  }

  @JsonCreator
  class List extends CustomResourceList[Cr] {}

}

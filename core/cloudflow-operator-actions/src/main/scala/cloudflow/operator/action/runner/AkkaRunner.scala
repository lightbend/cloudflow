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

import play.api.libs.json._
import skuber.Volume._
import skuber.apps.v1.Deployment
import skuber.json.rbac.format._
import skuber.rbac._
import skuber._
import cloudflow.blueprint.deployment._
import cloudflow.operator.action._

object AkkaRunner {
  final val Runtime                     = "akka"
  val JavaOptsEnvVar                    = "JAVA_OPTS"
  val PrometheusExporterRulesPathEnvVar = "PROMETHEUS_JMX_AGENT_CONFIG_PATH"
  val PrometheusExporterPortEnvVar      = "PROMETHEUS_JMX_AGENT_PORT"
  val DefaultReplicas                   = 1
  val ImagePullPolicy                   = Container.PullPolicy.Always

  val HealthCheckPath = "/checks/healthy"
  val ReadyCheckPath  = "/checks/ready"

  val ProbeInitialDelaySeconds = 10
  val ProbeTimeoutSeconds      = 1
  val ProbePeriodSeconds       = 10
}

/**
 * Creates the Resources that define an Akka [[Runner]].
 */
final class AkkaRunner(akkaRunnerDefaults: AkkaRunnerDefaults) extends Runner[Deployment] {
  import AkkaRunner._
  import akkaRunnerDefaults._
  def format = implicitly[Format[Deployment]]

  def editor = (obj: Deployment, newMetadata: ObjectMeta) => {
    obj.copy(metadata = newMetadata)
  }
  def configEditor       = (obj: ConfigMap, newMetadata: ObjectMeta) => obj.copy(metadata = newMetadata)
  val runtime            = Runtime
  def resourceDefinition = implicitly[ResourceDefinition[Deployment]]

  def appActions(app: CloudflowApplication.CR, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): Seq[Action] = {
    val roleAkka = akkaRole(app.namespace, labels, ownerReferences)
    Vector(
      Action.createOrUpdate(roleAkka, roleEditor),
      Action.createOrUpdate(akkaRoleBinding(app.namespace, roleAkka, labels, ownerReferences), roleBindingEditor)
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
    Action.createOrUpdate(labeledResource, editor)
  }

  def defaultReplicas                                   = DefaultReplicas
  def expectedPodCount(deployment: StreamletDeployment) = deployment.replicas.getOrElse(AkkaRunner.DefaultReplicas)

  private def akkaRole(namespace: String, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): Role =
    Role(
      metadata = ObjectMeta(
        name = Name.ofAkkaRole,
        namespace = namespace,
        labels = labels(Name.ofAkkaRole),
        ownerReferences = ownerReferences
      ),
      kind = "Role",
      rules = List(
        createAkkaClusterPolicyRule,
        createEventPolicyRule
      )
    )

  private def akkaRoleBinding(namespace: String, role: Role, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): RoleBinding =
    RoleBinding(
      metadata = ObjectMeta(
        name = Name.ofAkkaRoleBinding,
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
  private val createAkkaClusterPolicyRule = PolicyRule(
    apiGroups = List(""),
    attributeRestrictions = None,
    nonResourceURLs = List(),
    resourceNames = List(),
    resources = List("pods"),
    verbs = List("get", "list", "watch")
  )

  def prometheusConfig = PrometheusConfig(prometheusRules)

  def resource(
      deployment: StreamletDeployment,
      app: CloudflowApplication.CR,
      configSecret: skuber.Secret,
      updateLabels: Map[String, String] = Map()
  ): Deployment = {
    // The runtimeConfig is already applied in the runner config secret, so it can be safely ignored.

    val labels          = CloudflowLabels(app)
    val ownerReferences = List(OwnerReference(app.apiVersion, app.kind, app.metadata.name, app.metadata.uid, Some(true), Some(true)))
    val appId           = app.spec.appId
    val podName         = Name.ofPod(deployment.name)
    val k8sStreamletPorts =
      deployment.endpoint
        .map(endpoint => Container.Port(endpoint.containerPort, name = Name.ofContainerPort(endpoint.containerPort)))
        .toList
    val k8sPrometheusMetricsPort = Container.Port(PrometheusConfig.PrometheusJmxExporterPort, name = Name.ofContainerPrometheusExporterPort)

    val podsConfig = getPodsConfig(configSecret)

    // TODO check if this is still valid.
    // Pass this argument to the entry point script. The top level entry point will be a
    // cloudflow-entrypoint.sh which will route to the appropriate entry point based on the
    // arguments passed to it
    val args = List("akka")

    val configMapName = Name.ofConfigMap(deployment.name)

    val volume = Volume(configMapName, ConfigMapVolumeSource(configMapName))

    val streamletToDeploy = app.spec.streamlets.find(streamlet => streamlet.name == deployment.streamletName)

    val userConfiguredPorts = getPorts(podsConfig, PodsConfig.CloudflowPodName)
    // Streamlet volume mounting (Defined by Streamlet.volumeMounts API)
    val pvcRefVolumes =
      streamletToDeploy.map(_.descriptor.volumeMounts.map(mount => Volume(mount.name, PersistentVolumeClaimRef(mount.pvcName))).toList)
    val pvcVolumeMounts = streamletToDeploy
      .map(_.descriptor.volumeMounts.map { mount =>
        val readOnly = mount.accessMode match {
          case "ReadWriteMany" => false
          case "ReadOnlyMany"  => true
        }
        Volume.Mount(mount.name, mount.path, readOnly)
      }.toList)
      .getOrElse(List.empty)

    val secretName   = deployment.secretName
    val secretVolume = Volume(Name.ofVolume(secretName), Volume.Secret(secretName))
    val volumeMount  = Volume.Mount(configMapName, Runner.ConfigMapMountPath, readOnly = true)
    val secretMount  = Volume.Mount(Name.ofVolume(secretName), Runner.SecretMountPath, readOnly = true)

    val configSecretVolumes = getVolumes(podsConfig, PodsConfig.CloudflowPodName)

    val resourceRequirements = createResourceRequirements(podsConfig)
    val environmentVariables = createEnvironmentVariables(app, podsConfig)

    val c = Container(
      name = podName,
      resources = Some(resourceRequirements),
      image = deployment.image,
      env = environmentVariables,
      args = args,
      ports = k8sStreamletPorts ++ userConfiguredPorts :+ k8sPrometheusMetricsPort,
      volumeMounts = List(secretMount) ++ pvcVolumeMounts ++ getVolumeMounts(podsConfig, PodsConfig.CloudflowPodName) :+ volumeMount :+ Runner.DownwardApiVolumeMount
    )

    // See cloudflow.akkastream.internal.HealthCheckFiles
    val fileNameToCheckLiveness  = s"${deployment.streamletName}-live.txt"
    val fileNameToCheckReadiness = s"${deployment.streamletName}-ready.txt"

    val tempDir              = System.getProperty("java.io.tmpdir")
    val pathToLivenessCheck  = java.nio.file.Paths.get(tempDir, fileNameToCheckLiveness)
    val pathToReadinessCheck = java.nio.file.Paths.get(tempDir, fileNameToCheckReadiness)
    val container = c
      .withImagePullPolicy(ImagePullPolicy)
      .withLivenessProbe(
        Probe(
          ExecAction(List("/bin/sh", "-c", s"cat ${pathToLivenessCheck.toString} > /dev/null")),
          ProbeInitialDelaySeconds,
          ProbeTimeoutSeconds,
          Some(ProbePeriodSeconds)
        )
      )
      .withReadinessProbe(
        Probe(
          ExecAction(List("/bin/sh", "-c", s"cat ${pathToReadinessCheck.toString} > /dev/null")),
          ProbeInitialDelaySeconds,
          ProbeTimeoutSeconds,
          Some(ProbePeriodSeconds)
        )
      )

    // This is the group id of the user in the streamlet container,
    // it needs to make volumes managed by certain volume plugins writable.
    // If the image used with the container changes, this value most likely
    // will have to be updated
    val dockerContainerGroupId = Runner.DockerContainerGroupId
    // We only need to set this when we want to write to a volume in a pod
    val securityContext = pvcVolumeMounts
      .find(volume => volume.readOnly == false)
      .flatMap(_ => Some(PodSecurityContext(fsGroup = Some(dockerContainerGroupId))))

    val podSpec =
      Pod
        .Spec(serviceAccountName = Name.ofServiceAccount,
              volumes = pvcRefVolumes.getOrElse(List.empty[Volume]),
              securityContext = securityContext)
        .addContainer(container)
        .addVolume(volume)
        .addVolume(secretVolume)
        .addVolume(Runner.DownwardApiVolume)

    val podSpecSecretVolumesAdded = configSecretVolumes.foldLeft[Pod.Spec](podSpec) {
      case (acc, curr) =>
        acc.addVolume(curr)
    }

    val template =
      Pod.Template.Spec
        .named(podName)
        .addLabels(
          labels.withComponent(podName, CloudflowLabels.StreamletComponent) ++ Map(
                CloudflowLabels.StreamletNameLabel -> deployment.streamletName,
                CloudflowLabels.AppIdLabel         -> appId
              ).toMap.mapValues(Name.ofLabelValue) ++ getLabels(podsConfig, PodsConfig.CloudflowPodName)
        )
        .addAnnotation("prometheus.io/scrape" -> "true")
        .addLabels(updateLabels)
        .withPodSpec(podSpecSecretVolumesAdded)

    val deploymentResource = Deployment(
      metadata = ObjectMeta(name = podName,
                            namespace = app.namespace,
                            labels = labels.withComponent(podName, CloudflowLabels.StreamletComponent),
                            ownerReferences = ownerReferences)
    ).withReplicas(deployment.replicas.getOrElse(DefaultReplicas))
      .withTemplate(template)
      .withLabelSelector(LabelSelector(LabelSelector.IsEqualRequirement(CloudflowLabels.Name, podName)))

    deploymentResource.copy(
      spec = deploymentResource.spec.map(s =>
        s.copy(strategy = deployment.endpoint
          .map(_ => Deployment.Strategy(Deployment.StrategyType.RollingUpdate))
          .orElse(Some(Deployment.Strategy(Deployment.StrategyType.Recreate)))
        )
      )
    )
  }

  def resourceName(deployment: StreamletDeployment): String = Name.ofPod(deployment.name)

  private def createResourceRequirements(podsConfig: PodsConfig) = {
    var resourceRequirements = Resource.Requirements(
      requests = Map(
        Resource.cpu    -> resourceConstraints.cpuRequests,
        Resource.memory -> resourceConstraints.memoryRequests
      )
    )

    resourceRequirements = resourceConstraints.cpuLimits
      .map { cpuLimit =>
        resourceRequirements.copy(limits = resourceRequirements.limits + (Resource.cpu -> cpuLimit))
      }
      .getOrElse(resourceRequirements)

    resourceRequirements = resourceConstraints.memoryLimits
      .map { memoryLimit =>
        resourceRequirements.copy(limits = resourceRequirements.limits + (Resource.memory -> memoryLimit))
      }
      .getOrElse(resourceRequirements)
    podsConfig.pods
      .get(PodsConfig.CloudflowPodName)
      .flatMap { podConfig =>
        podConfig.containers.get(PodsConfig.CloudflowContainerName).map { containerConfig =>
          resourceRequirements.copy(
            limits = resourceRequirements.limits ++ containerConfig.resources.map(_.limits).getOrElse(Map()),
            requests = resourceRequirements.requests ++ containerConfig.resources.map(_.requests).getOrElse(Map())
          )
        }
      }
      .getOrElse(resourceRequirements)
  }

  private def createEnvironmentVariables(app: CloudflowApplication.CR, podsConfig: PodsConfig) = {
    val agentPaths = app.spec.agentPaths
    val prometheusEnvVars = if (agentPaths.contains(CloudflowApplication.PrometheusAgentKey)) {
      List(
        EnvVar(PrometheusExporterPortEnvVar, PrometheusConfig.PrometheusJmxExporterPort.toString),
        EnvVar(PrometheusExporterRulesPathEnvVar, PrometheusConfig.prometheusConfigPath(Runner.ConfigMapMountPath))
      )
    } else Nil

    val defaultEnvironmentVariables = EnvVar(JavaOptsEnvVar, javaOptions) :: prometheusEnvVars
    val envVarsFomPodConfigMap = podsConfig.pods
      .get(PodsConfig.CloudflowPodName)
      .flatMap { podConfig =>
        podConfig.containers.get(PodsConfig.CloudflowContainerName).map { containerConfig =>
          containerConfig.env
        }
      }
      .toList
      .flatten
      .map { envVar =>
        envVar.name -> envVar
      }
      .toMap

    val defaultEnvironmentVariablesMap = defaultEnvironmentVariables.map { envVar =>
      envVar.name -> envVar
    }.toMap

    (defaultEnvironmentVariablesMap ++ envVarsFomPodConfigMap).values.toList
  }
}

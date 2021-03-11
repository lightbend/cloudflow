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
import akka.kube.actions.Action
import cloudflow.blueprint.deployment.PrometheusConfig
import cloudflow.operator.action._
import io.fabric8.kubernetes.api.model.apps.{
  Deployment,
  DeploymentBuilder,
  DeploymentSpecBuilder,
  DeploymentStrategy,
  DeploymentStrategyBuilder
}
import io.fabric8.kubernetes.api.model.rbac.{
  PolicyRuleBuilder,
  Role,
  RoleBinding,
  RoleBindingBuilder,
  RoleBuilder,
  RoleRefBuilder,
  SubjectBuilder
}
import io.fabric8.kubernetes.api.model.{
  ConfigMapVolumeSourceBuilder,
  ContainerBuilder,
  ContainerPortBuilder,
  EnvVarBuilder,
  ExecActionBuilder,
  OwnerReference,
  OwnerReferenceBuilder,
  PersistentVolumeClaimVolumeSourceBuilder,
  PodSecurityContextBuilder,
  PodSpecBuilder,
  PodTemplateSpecBuilder,
  ProbeBuilder,
  ResourceRequirementsBuilder,
  Secret,
  SecretVolumeSourceBuilder,
  Volume,
  VolumeBuilder,
  VolumeMountBuilder
}
import scala.jdk.CollectionConverters._

object AkkaRunner {
  final val Runtime = "akka"
  val JavaOptsEnvVar = "JAVA_OPTS"
  val PrometheusExporterRulesPathEnvVar = "PROMETHEUS_JMX_AGENT_CONFIG_PATH"
  val PrometheusExporterPortEnvVar = "PROMETHEUS_JMX_AGENT_PORT"
  val DefaultReplicas = 1
  val ImagePullPolicy = "Always"

  val ProbeInitialDelaySeconds = 10
  val ProbeTimeoutSeconds = 1
  val ProbePeriodSeconds = 10
}

/**
 * Creates the Resources that define an Akka [[Runner]].
 */
final class AkkaRunner(akkaRunnerDefaults: AkkaRunnerDefaults) extends Runner[Deployment] {
  import AkkaRunner._
  import akkaRunnerDefaults._

  val runtime = Runtime

  def appActions(app: App.Cr, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): Seq[Action] = {
    val roleAkka = akkaRole(app.namespace, labels, ownerReferences)
    Seq(
      Action.createOrReplace(roleAkka),
      Action.createOrReplace(akkaRoleBinding(app.namespace, roleAkka, labels, ownerReferences)))
  }

  def streamletChangeAction(
      app: App.Cr,
      runners: Map[String, Runner[_]],
      streamletDeployment: App.Deployment,
      secret: Secret) = {
    Action.get[Deployment](streamletDeployment.name, app.namespace) { currentDeployment =>
      currentDeployment match {
        case Some(dep) =>
          val meta = dep.getMetadata
          val labels = meta.getLabels
          labels.put(CloudflowLabels.ConfigUpdateLabel, System.currentTimeMillis.toString)
          meta.setLabels(labels)

          Action.createOrReplace(
            new DeploymentBuilder(dep)
              .withMetadata(meta)
              .build())
        case _ =>
          val templateDeployment =
            resource(
              streamletDeployment,
              app,
              secret,
              Map((CloudflowLabels.ConfigUpdateLabel -> System.currentTimeMillis.toString)))
          Action.createOrReplace(templateDeployment)
      }
    }
  }

  def defaultReplicas = DefaultReplicas
  def expectedPodCount(deployment: App.Deployment) = deployment.replicas.getOrElse(AkkaRunner.DefaultReplicas)

  private def akkaRole(namespace: String, labels: CloudflowLabels, ownerReferences: List[OwnerReference]): Role = {
    new RoleBuilder()
      .withNewMetadata()
      .withName(Name.ofAkkaRole)
      .withNamespace(namespace)
      .withLabels(labels(Name.ofAkkaRole).asJava)
      .withOwnerReferences(ownerReferences: _*)
      .endMetadata()
      .withKind("Role")
      .withRules(createAkkaClusterPolicyRule, createEventPolicyRule)
      .build()
  }

  private def akkaRoleBinding(
      namespace: String,
      role: Role,
      labels: CloudflowLabels,
      ownerReferences: List[OwnerReference]): RoleBinding = {
    new RoleBindingBuilder()
      .withNewMetadata()
      .withName(Name.ofAkkaRoleBinding)
      .withNamespace(namespace)
      .withLabels(labels(Name.ofRoleBinding).asJava)
      .withOwnerReferences(ownerReferences: _*)
      .endMetadata()
      .withKind("RoleBinding")
      .withRoleRef(
        new RoleRefBuilder()
          .withApiGroup("rbac.authorization.k8s.io")
          .withKind("Role")
          .withName(role.getMetadata.getName)
          .build())
      .withSubjects(
        new SubjectBuilder()
          .withKind("ServiceAccount")
          .withName(Name.ofServiceAccount)
          .withNamespace(namespace)
          .build())
      .build()
  }

  private val createAkkaClusterPolicyRule = {
    new PolicyRuleBuilder()
      .withApiGroups("")
      .withResources("pods")
      .withVerbs("get", "list", "watch")
      .build()
  }

  def prometheusConfig = PrometheusConfig(prometheusRules)

  def resource(
      deployment: App.Deployment,
      app: App.Cr,
      configSecret: Secret,
      updateLabels: Map[String, String] = Map()): Deployment = {
    // The runtimeConfig is already applied in the runner config secret, so it can be safely ignored.

    val labels = CloudflowLabels(app)
    val ownerReferences = List(
      // TODO: this is repeated in other places ...
      new OwnerReferenceBuilder()
        .withApiVersion(app.getApiVersion)
        .withKind(app.getKind)
        .withName(app.getMetadata.getName)
        .withUid(app.getMetadata.getUid)
        .withController(true)
        .withBlockOwnerDeletion(true)
        .build())
    val appId = app.spec.appId
    val podName = Name.ofPod(deployment.name)
    val k8sStreamletPorts =
      deployment.endpoint
        .map { endpoint =>
          endpoint.containerPort match {
            case Some(cp) =>
              Some(
                new ContainerPortBuilder()
                  .withContainerPort(cp)
                  .withName(Name.ofContainerPort(cp))
                  .build())
            case _ => None
          }
        }
        .flatten
        .toList

    val k8sPrometheusMetricsPort =
      new ContainerPortBuilder()
        .withContainerPort(PrometheusConfig.PrometheusJmxExporterPort)
        .withName(Name.ofContainerPrometheusExporterPort)
        .build()

    val podsConfig = getPodsConfig(configSecret)

    // TODO check if this is still valid.
    // Pass this argument to the entry point script. The top level entry point will be a
    // cloudflow-entrypoint.sh which will route to the appropriate entry point based on the
    // arguments passed to it
    val args = List("akka")

    val configMapName = Name.ofConfigMap(deployment.name)

    val volume = {
      new VolumeBuilder()
        .withName(configMapName)
        .withConfigMap(
          new ConfigMapVolumeSourceBuilder()
            .withName(configMapName)
            .build())
        .build()
    }

    val streamletToDeploy = app.spec.streamlets.find(streamlet => streamlet.name == deployment.streamletName)

    val userConfiguredPorts = getContainerPorts(podsConfig, PodsConfig.CloudflowPodName)
    // Streamlet volume mounting (Defined by Streamlet.volumeMounts API)
    val pvcRefVolumes =
      streamletToDeploy.map(_.descriptor.volumeMounts.map { mount =>
        new VolumeBuilder()
          .withName(mount.appId)
          .withPersistentVolumeClaim(
            new PersistentVolumeClaimVolumeSourceBuilder()
              .withClaimName(mount.pvcName.getOrElse(""))
              .build())
          .build()
      }.toList)
    val pvcVolumeMounts = streamletToDeploy
      .map(_.descriptor.volumeMounts.map { mount =>
        val readOnly = mount.accessMode match {
          case "ReadWriteMany" => false
          case "ReadOnlyMany"  => true
        }

        new VolumeMountBuilder()
          .withName(mount.appId)
          .withMountPath(mount.path)
          .withReadOnly(readOnly)
          .build()
      }.toList)
      .getOrElse(List.empty)

    val secretName = deployment.secretName
    val secretVolume = {
      new VolumeBuilder()
        .withName(Name.ofVolume(secretName))
        .withSecret(
          new SecretVolumeSourceBuilder()
            .withSecretName(secretName)
            .build())
        .build()
    }
    val volumeMount = {
      new VolumeMountBuilder()
        .withName(configMapName)
        .withMountPath(Runner.ConfigMapMountPath)
        .withReadOnly(true)
        .build()
    }
    val secretMount = {
      new VolumeMountBuilder()
        .withName(Name.ofVolume(secretName))
        .withMountPath(Runner.SecretMountPath)
        .withReadOnly(true)
        .build()
    }

    val configSecretVolumes = getVolumes(podsConfig, PodsConfig.CloudflowPodName)

    val resourceRequirements = createResourceRequirements(podsConfig)
    val environmentVariables = createEnvironmentVariables(app, podsConfig)

    val c: ContainerBuilder = {
      new ContainerBuilder()
        .withName(podName)
        .withResources(resourceRequirements)
        .withImage(deployment.image)
        .withEnv(environmentVariables: _*)
        .withArgs(args: _*)
        .withPorts((k8sStreamletPorts ++ userConfiguredPorts :+ k8sPrometheusMetricsPort): _*)
        .withVolumeMounts((List(secretMount) ++ pvcVolumeMounts ++ getVolumeMounts(
          podsConfig,
          PodsConfig.CloudflowPodName) :+ volumeMount :+ Runner.DownwardApiVolumeMount): _*)
    }

    // See cloudflow.akkastream.internal.HealthCheckFiles
    val fileNameToCheckLiveness = s"${deployment.streamletName}-live.txt"
    val fileNameToCheckReadiness = s"${deployment.streamletName}-ready.txt"

    val tempDir = "/tmp"
    val pathToLivenessCheck = java.nio.file.Paths.get(tempDir, fileNameToCheckLiveness)
    val pathToReadinessCheck = java.nio.file.Paths.get(tempDir, fileNameToCheckReadiness)
    val container = c
      .withImagePullPolicy(ImagePullPolicy)
      .withLivenessProbe(
        new ProbeBuilder()
          .withExec(new ExecActionBuilder()
            .withCommand("/bin/sh", "-c", s"cat ${pathToLivenessCheck.toString} > /dev/null")
            .build())
          .withInitialDelaySeconds(ProbeInitialDelaySeconds)
          .withTimeoutSeconds(ProbeTimeoutSeconds)
          .withPeriodSeconds(ProbePeriodSeconds)
          .build())
      .withReadinessProbe(
        new ProbeBuilder()
          .withExec(new ExecActionBuilder()
            .withCommand("/bin/sh", "-c", s"cat ${pathToReadinessCheck.toString} > /dev/null")
            .build())
          .withInitialDelaySeconds(ProbeInitialDelaySeconds)
          .withTimeoutSeconds(ProbeTimeoutSeconds)
          .withPeriodSeconds(ProbePeriodSeconds)
          .build())
      .build()

    // This is the group id of the user in the streamlet container,
    // it needs to make volumes managed by certain volume plugins writable.
    // If the image used with the container changes, this value most likely
    // will have to be updated
    val dockerContainerGroupId = Runner.DockerContainerGroupId
    // We only need to set this when we want to write to a volume in a pod
    val securityContext = pvcVolumeMounts
      .find(volume => volume.getReadOnly == false)
      .flatMap(_ => Some(new PodSecurityContextBuilder().withFsGroup(dockerContainerGroupId).build()))

    val podSpec = {
      val allVolumes: List[Volume] =
        List(volume, secretVolume, Runner.DownwardApiVolume) ++
        pvcRefVolumes.getOrElse(List.empty) ++
        configSecretVolumes

      val podSpecBuilder = new PodSpecBuilder()
        .withServiceAccount(Name.ofServiceAccount)
        .withVolumes(allVolumes.asJava)
        .withContainers(container)

      (securityContext match {
        case Some(sc) =>
          podSpecBuilder.withSecurityContext(sc)
        case _ =>
          podSpecBuilder
      }).build()
    }

    val template = {

      new PodTemplateSpecBuilder()
        .withNewMetadata()
        .withName(podName)
        .withLabels(
          (labels.withComponent(podName, CloudflowLabels.StreamletComponent) ++ Map(
            CloudflowLabels.StreamletNameLabel -> deployment.streamletName,
            CloudflowLabels.AppIdLabel -> appId).view
            .mapValues(Name.ofLabelValue) ++ getLabels(podsConfig, PodsConfig.CloudflowPodName) ++ updateLabels).asJava)
        .withAnnotations((Map("prometheus.io/scrape" -> "true") ++
        getAnnotations(podsConfig, PodsConfig.CloudflowPodName)).asJava)
        .endMetadata()
        .withSpec(podSpec)
        .build()
    }

    val deploymentStrategy: DeploymentStrategy = {
      if (deployment.endpoint.isDefined) {
        // TODO: not sure how this works
        new DeploymentStrategyBuilder()
          .withType("RollingUpdate")
          .withNewRollingUpdate()
          .endRollingUpdate()
          .build()
      } else {
        new DeploymentStrategyBuilder()
          .withType("Recreate")
          .build()
      }
    }

    val deploymentResource = {
      new DeploymentBuilder()
        .withNewMetadata()
        .withName(podName)
        .withNamespace(app.namespace)
        .withLabels(labels.withComponent(podName, CloudflowLabels.StreamletComponent).asJava)
        .withOwnerReferences(ownerReferences: _*)
        .endMetadata()
        .withSpec(
          new DeploymentSpecBuilder()
            .withNewSelector()
            .withMatchLabels(Map(CloudflowLabels.Name -> podName).asJava)
            .endSelector()
            .withReplicas(Integer.valueOf(deployment.replicas.getOrElse(DefaultReplicas)))
            .withTemplate(template)
            .withStrategy(deploymentStrategy)
            .build())
        .build()
    }

    deploymentResource
  }

  def resourceName(deployment: App.Deployment): String = Name.ofPod(deployment.name)

  private def createResourceRequirements(podsConfig: PodsConfig) = {
    val limits = {
      resourceConstraints.cpuLimits.map(v => "cpu" -> v).toMap ++
      resourceConstraints.memoryLimits.map(v => "memory" -> v).toMap
    }

    val requests = {
      Map("cpu" -> resourceConstraints.cpuRequests, "memory" -> resourceConstraints.memoryRequests)
    }

    lazy val resourceRequirements =
      new ResourceRequirementsBuilder()
        .withLimits(limits.asJava)
        .withRequests(requests.asJava)
        .build()

    // TODO slightly changed the logic / doublecheck the correctness
    // e.g. now we go with "less defaults" probably
    (for {
      pod <- podsConfig.pods.get(PodsConfig.CloudflowPodName)
      containerConfig <- pod.containers.get(PodsConfig.CloudflowContainerName)
      resources <- containerConfig.resources
    } yield {
      resources
    }).getOrElse(resourceRequirements)
  }

  private def createEnvironmentVariables(app: App.Cr, podsConfig: PodsConfig) = {
    val agentPaths = app.spec.agentPaths
    val prometheusEnvVars = if (agentPaths.contains(Util.PrometheusAgentKey)) {
      List(
        new EnvVarBuilder()
          .withName(PrometheusExporterPortEnvVar)
          .withValue(PrometheusConfig.PrometheusJmxExporterPort.toString)
          .build(),
        new EnvVarBuilder()
          .withName(PrometheusExporterRulesPathEnvVar)
          .withValue(PrometheusConfig.prometheusConfigPath(Runner.ConfigMapMountPath))
          .build())
    } else Nil

    val defaultEnvironmentVariables = new EnvVarBuilder()
        .withName(JavaOptsEnvVar)
        .withValue(javaOptions)
        .build() :: prometheusEnvVars
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
        envVar.getName -> envVar
      }
      .toMap

    val defaultEnvironmentVariablesMap = defaultEnvironmentVariables.map { envVar =>
      envVar.getName -> envVar
    }.toMap

    (defaultEnvironmentVariablesMap ++ envVarsFomPodConfigMap).values.toList
  }
}

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

import cloudflow.blueprint.deployment._
import skuber._
import play.api.libs.json._
import skuber.Volume._
import skuber.apps.v1.Deployment

/**
 * Creates the ConfigMap and the Runner resource (a Deployment) that define an Akka [[Runner]].
 */
object AkkaRunner extends Runner[Deployment] {
  def format = implicitly[Format[Deployment]]

  def editor = (obj: Deployment, newMetadata: ObjectMeta) ⇒ {
    obj.copy(metadata = newMetadata)
  }
  def configEditor = (obj: ConfigMap, newMetadata: ObjectMeta) ⇒ obj.copy(metadata = newMetadata)

  def resourceDefinition = implicitly[ResourceDefinition[Deployment]]
  val runtime            = "akka"

  def resource(
      deployment: StreamletDeployment,
      app: CloudflowApplication.CR,
      namespace: String,
      updateLabels: Map[String, String]
  )(implicit ctx: DeploymentContext): Deployment = {
    val labels  = CloudflowLabels(app)
    val ownerReferences = CloudflowOwnerReferences(app)
    val appId   = app.spec.appId
    val podName = Name.ofPod(deployment.name)
    val k8sStreamletPorts =
      deployment.endpoint.map(endpoint ⇒ Container.Port(endpoint.containerPort, name = Name.ofContainerPort(endpoint.containerPort))).toList
    val k8sPrometheusMetricsPort = Container.Port(PrometheusConfig.PrometheusJmxExporterPort, name = Name.ofContainerPrometheusExporterPort)

    val environmentVariables = List(
      EnvVar(JavaOptsEnvVar, ctx.akkaRunnerSettings.javaOptions),
      EnvVar(PrometheusExporterPortEnvVar, PrometheusConfig.PrometheusJmxExporterPort.toString),
      EnvVar(PrometheusExporterRulesPathEnvVar, PrometheusConfig.prometheusConfigPath(Runner.ConfigMapMountPath))
    )

    // Pass this argument to the entry point script. The top level entry point will be a
    // cloudflow-entrypoint.sh which will route to the appropriate entry point based on the
    // arguments passed to it
    val args = List("akka")

    val configMapName = Name.ofConfigMap(deployment.name)

    val volume = Volume(configMapName, ConfigMapVolumeSource(configMapName))

    // Streamlet volume mounting
    val streamletToDeploy = app.spec.streamlets.find(streamlet ⇒ streamlet.name == deployment.streamletName)
    val pvcRefVolumes =
      streamletToDeploy.map(_.descriptor.volumeMounts.map(mount ⇒ Volume(mount.name, PersistentVolumeClaimRef(mount.pvcName))).toList)
    val pvcVolumeMounts = streamletToDeploy
      .map(_.descriptor.volumeMounts.map { mount ⇒
        val readOnly = mount.accessMode match {
          case "ReadWriteMany" ⇒ false
          case "ReadOnlyMany"  ⇒ true
        }
        Volume.Mount(mount.name, mount.path, readOnly)
      }.toList)
      .getOrElse(List.empty)

    val secretName   = deployment.secretName
    val secretVolume = Volume(Name.ofVolume(secretName), Volume.Secret(secretName))
    val volumeMount  = Volume.Mount(configMapName, Runner.ConfigMapMountPath, readOnly = true)
    val secretMount  = Volume.Mount(Name.ofVolume(secretName), Runner.SecretMountPath, readOnly = true)

    val c = Container(
      name = podName,
      image = deployment.image,
      env = environmentVariables,
      args = args,
      ports = k8sStreamletPorts :+ k8sPrometheusMetricsPort,
      volumeMounts = List(secretMount) ++ pvcVolumeMounts :+ volumeMount :+ Runner.DownwardApiVolumeMount
    )
    val appliedCpuLimits = ctx.akkaRunnerSettings.resourceConstraints.cpuLimits
      .map { cpuLimits ⇒
        c.limitCPU(cpuLimits)
      }
      .getOrElse(c)

    val appliedCpuAndMemoryLimits = ctx.akkaRunnerSettings.resourceConstraints.memoryLimits
      .map { memoryLimits ⇒
        appliedCpuLimits.limitMemory(memoryLimits)
      }
      .getOrElse(appliedCpuLimits)

    val fileNameToCheckLiveness  = s"${deployment.streamletName}-live.txt"
    val fileNameToCheckReadiness = s"${deployment.streamletName}-ready.txt"

    val tempDir              = System.getProperty("java.io.tmpdir")
    val pathToLivenessCheck  = java.nio.file.Paths.get(tempDir, fileNameToCheckLiveness)
    val pathToReadinessCheck = java.nio.file.Paths.get(tempDir, fileNameToCheckReadiness)

    val container = appliedCpuAndMemoryLimits
      .withImagePullPolicy(ImagePullPolicy)
      .requestCPU(ctx.akkaRunnerSettings.resourceConstraints.cpuRequests)
      .requestMemory(ctx.akkaRunnerSettings.resourceConstraints.memoryRequests)
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
    // its need to make volumes managed by certain volume plugins writable.
    // If the image used with the container changes, this value most likely
    // have to be updated
    val dockerContainerGroupId = Runner.DockerContainerGroupId
    // We only need to set this when we want to write to the a volume in a pod
    val fsGroup = pvcVolumeMounts
      .find(volume ⇒ volume.readOnly == true)
      .flatMap(_ ⇒ Some(PodSecurityContext(fsGroup = Some(dockerContainerGroupId))))

    val podSpec =
      Pod
        .Spec(serviceAccountName = Name.ofServiceAccount(),
              volumes = pvcRefVolumes.getOrElse(List.empty[Volume]),
              securityContext = fsGroup)
        .addContainer(container)
        .addVolume(volume)
        .addVolume(secretVolume)
        .addVolume(Runner.DownwardApiVolume)

    val template =
      Pod.Template.Spec
        .named(podName)
        .addLabels(
          labels.withComponent(podName, CloudflowLabels.StreamletComponent) ++ Map(Operator.StreamletNameLabel -> deployment.streamletName,
                                                                                   Operator.AppIdLabel         -> appId)
        )
        .addAnnotation("prometheus.io/scrape" -> "true")
        .addLabels(updateLabels)
        .withPodSpec(podSpec)

    val deploymentResource = Deployment(
      metadata =
        ObjectMeta(name = podName, namespace = namespace, labels = labels.withComponent(podName, CloudflowLabels.StreamletComponent), ownerReferences = ownerReferences.list)
    ).withReplicas(deployment.replicas.getOrElse(NrOfReplicas))
      .withTemplate(template)
      .withLabelSelector(LabelSelector(LabelSelector.IsEqualRequirement(CloudflowLabels.Name, podName)))

    deploymentResource.copy(
      spec = deploymentResource.spec.map(s ⇒
        s.copy(strategy = deployment.endpoint
          .map(_ ⇒ Deployment.Strategy(Deployment.StrategyType.RollingUpdate))
          .orElse(Some(Deployment.Strategy(Deployment.StrategyType.Recreate)))
        )
      )
    )
  }

  val JavaOptsEnvVar                    = "JAVA_OPTS"
  val PrometheusExporterRulesPathEnvVar = "PROMETHEUS_JMX_AGENT_CONFIG_PATH"
  val PrometheusExporterPortEnvVar      = "PROMETHEUS_JMX_AGENT_PORT"
  val NrOfReplicas                      = 1
  val ImagePullPolicy                   = Container.PullPolicy.Always

  val HealthCheckPath = "/checks/healthy"
  val ReadyCheckPath  = "/checks/ready"

  val ProbeInitialDelaySeconds = 10
  val ProbeTimeoutSeconds      = 1
  val ProbePeriodSeconds       = 10
}

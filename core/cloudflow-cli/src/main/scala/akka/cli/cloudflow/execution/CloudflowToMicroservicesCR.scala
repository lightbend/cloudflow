/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import akka.cli.cloudflow.kubeclient.KubeClient
import akka.cli.microservice.{ AkkaMicroserviceSpec, ConfigSecret, LogbackSecret, SecretVolume }
import akka.datap.crd.App
import com.fasterxml.jackson.databind.JsonNode
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.client.utils.Serialization

object CloudflowToMicroservicesCR {

  private def toJson[R <: KubernetesResource](resource: R): JsonNode =
    Serialization
      .jsonMapper()
      .readTree(Serialization.asJson(resource))

  private val podTemplate = {
    new PodTemplateSpecBuilder()
      .withNewSpec()
      .withVolumes(
        new VolumeBuilder()
          .withName("downward-api-volume")
          .withNewDownwardAPI()
          .withItems(
            new DownwardAPIVolumeFileBuilder()
              .withNewPath("metadata.uid")
              .withNewFieldRef()
              .withNewFieldPath("metadata.uid")
              .endFieldRef()
              .build(),
            new DownwardAPIVolumeFileBuilder()
              .withNewPath("metadata.name")
              .withNewFieldRef()
              .withNewFieldPath("metadata.name")
              .endFieldRef()
              .build(),
            new DownwardAPIVolumeFileBuilder()
              .withNewPath("metadata.namespace")
              .withNewFieldRef()
              .withNewFieldPath("metadata.namespace")
              .endFieldRef()
              .build())
          .endDownwardAPI()
          .build())
      .endSpec()
      .build()
  }

  private val extraVolumeMounts = {
    new VolumeMountBuilder()
      .withNewName("downward-api-volume")
      .withMountPath("/mnt/downward-api-volume")
      .build()
  }

  def probe(appName: String, suffix: String) = {
    new ProbeBuilder()
      .withNewExec()
      .withCommand("/bin/sh", "-c", s"cat /tmp/${appName}-${suffix}.txt")
      .endExec()
      .withInitialDelaySeconds(10)
      .withTimeoutSeconds(1)
      .withPeriodSeconds(10)
      .build()
  }

  def convert(spec: App.Spec, logbackSecret: Option[String]): Map[String, Option[AkkaMicroserviceSpec]] = {
    spec.deployments.map { deployment =>
      if (deployment.runtime == "akka") {
        deployment.streamletName -> Some(
          AkkaMicroserviceSpec(
            replicas = deployment.replicas.getOrElse(1),
            image = deployment.image,
            imagePullPolicy = Some("Always"),
            appVersion = Some(spec.appVersion),
            imagePullSecrets = Seq(KubeClient.ImagePullSecretName),
            readinessProbe = Some(toJson(probe(deployment.streamletName, "ready"))),
            livenessProbe = Some(toJson(probe(deployment.streamletName, "live"))),
            // configSecret = Some(ConfigSecret(deployment.secretName)),
            javaOptions = "-Dconfig.file=/etc/cloudflow-runner-secret/application.conf",
            secretVolumes =
              Seq(SecretVolume(secretName = deployment.secretName, mountPath = "/etc/cloudflow-runner-secret")),
            logbackSecret = logbackSecret.map(LogbackSecret),
            podTemplateSpec = Some(toJson(podTemplate)),
            extraVolumeMounts = Seq(toJson(extraVolumeMounts))))
      } else {
        deployment.streamletName -> None
      }
    }.toMap
  }

}

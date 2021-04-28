/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cli

import akka.cloudflow.config.CloudflowConfig._

import java.io._
import com.typesafe.config.ConfigFactory
import pureconfig._
import pureconfig.generic.auto.exportWriter

object SamplesGenerator extends App {

  def generateCloudflowCliConfigurationSample(): String = {
    val fullConfig = CloudflowRoot(cloudflow = Cloudflow(
      streamlets = Map(
        "my-streamlet" -> Streamlet(
          configParameters = ConfigFactory.empty(),
          config = ConfigFactory.empty(),
          kubernetes = Kubernetes(pods = Map(
            "my-pod" ->
            Pod(
              labels = Map(LabelKey("labelKey") -> LabelValue("labelValue")),
              volumes = Map(
                "my-pvc" -> PvcVolume(name = "cloudflow-pvc", readOnly = false),
                "my-secret" -> SecretVolume(name = "secret.conf")),
              containers = Map("my-container" -> Container(
                env = Some(List(EnvVar("ENV_VAR_KEY", "ENV_VAR_VALUE"))),
                resources = Requirements(
                  requests = Requirement(cpu = Some(Quantity("1")), memory = Some(Quantity("1Gb"))),
                  limits = Requirement(cpu = Some(Quantity("2")), memory = Some(Quantity("2Gb")))),
                volumeMounts = Map("my-pvc" ->
                VolumeMount(mountPath = "/mnt", readOnly = false, subPath = "/tmp"))))))))),
      runtimes = Map(
        "akka" -> Runtime(
          config = ConfigFactory.empty(),
          kubernetes = Kubernetes(pods = Map(
            "my-pod" ->
            Pod(
              labels = Map(LabelKey("labelKey") -> LabelValue("labelValue")),
              volumes = Map(
                "my-pvc" -> PvcVolume(name = "cloudflow-pvc", readOnly = false),
                "my-secret" -> SecretVolume(name = "secret.conf")),
              containers = Map("my-container" -> Container(
                env = Some(List(EnvVar("ENV_VAR_KEY", "ENV_VAR_VALUE"))),
                resources = Requirements(
                  requests = Requirement(cpu = Some(Quantity("1")), memory = Some(Quantity("1Gb"))),
                  limits = Requirement(cpu = Some(Quantity("2")), memory = Some(Quantity("2Gb")))),
                volumeMounts = Map("my-pvc" ->
                VolumeMount(mountPath = "/mnt", readOnly = false, subPath = "/tmp"))))))))),
      topics = Map("my-topic" -> Topic())))

    ConfigFactory
      .empty()
      .withFallback(ConfigWriter[CloudflowRoot].to(fullConfig))
      .root()
      .render(
        com.typesafe.config.ConfigRenderOptions
          .defaults()
          .setJson(false)
          .setFormatted(true)
          .setOriginComments(false)
          .setComments(true))
  }

  def dumpToFile(fileName: String, content: String) = {
    val file = new File(fileName)
    if (file.exists()) file.delete()
    val pw = new PrintWriter(new File(fileName))
    pw.write(content)
    pw.close
  }

  common.FileUtils.dumpToFile("cloudflow-cli/samples/full-config.conf", generateCloudflowCliConfigurationSample())
}

/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import scala.util.Try
import akka.cli.cloudflow.CliLogger
import akka.cloudflow.config.CloudflowConfig
import akka.datap.crd.App
import com.typesafe.config.ConfigFactory
import io.fabric8.kubernetes.client.utils.Serialization
import org.scalatest.TryValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WithUpdateVolumesMountsSpec extends AnyFlatSpec with WithUpdateVolumeMounts with Matchers with TryValues {
  val emptyConfig = Serialization.jsonMapper().readTree("{}")
  val streamletName = "my-streamlet"
  def crWithVolumeMounts(volumeMounts: Seq[App.VolumeMountDescriptor]) = {
    App.Cr(
      metadata = null,
      spec = App.Spec(
        appId = "",
        appVersion = "",
        deployments = Seq(
          App.Deployment(
            name = "some-app-id",
            runtime = "akka",
            image = "docker-registry.foo.com/lightbend/call-record-pipeline:277-ceb9629",
            streamletName = streamletName,
            className = "cloudflow.operator.runner.AkkaRunner",
            secretName = streamletName,
            config = emptyConfig,
            portMappings = Map("valid" -> App
              .PortMapping(id = "valid-metrics", config = null, cluster = Some("deployment-named-cluster"))),
            volumeMounts = volumeMounts,
            endpoint = None,
            replicas = None)),
        agentPaths = Map(),
        version = None,
        libraryVersion = None,
        serviceAccount = None,
        streamlets = Seq(
          App.Streamlet(
            name = streamletName,
            descriptor = App.Descriptor(
              attributes = Seq(),
              className = "",
              volumeMounts = volumeMounts,
              inlets = Seq(),
              labels = Seq(),
              outlets = Seq(),
              runtime = "",
              description = "",
              configParameters = Seq())))))
  }

  "--volume-mount" should "not update the CR when not specified" in {
    // Arrange
    val appCr = crWithVolumeMounts(Seq())

    // Act
    val updatedCr = updateVolumeMounts(appCr, Map(), () => Try(List("pvc-name")))

    // Assert
    updatedCr.success.value shouldBe appCr
  }

  it should "update CR deployments and descriptors if specified correctly and pvcs can be found" in {
    val volumeName = "volume"
    // Arrange
    val volumeMountFromApi = App.VolumeMountDescriptor(volumeName, "/mnt/data", "ReadWriteMany", Some(""))
    val expectedVolumeMount = App.VolumeMountDescriptor(volumeName, "/mnt/data", "ReadWriteMany", Some("my-pvc"))
    val appCr = crWithVolumeMounts(Seq(volumeMountFromApi))

    // Act
    val updatedCr =
      updateVolumeMounts(
        appCr,
        Map(s"$streamletName.$volumeName" -> "my-pvc"),
        () => Try(List("my-pvc", "my-other-pvc")))

    // Assert
    val volumeMountsInDescriptor =
      updatedCr.success.value.spec.streamlets
        .find(_.name == streamletName)
        .map(_.descriptor.volumeMounts)
        .getOrElse(Seq())
    volumeMountsInDescriptor should contain(expectedVolumeMount)
    val volumeMountsInDeployment =
      updatedCr.success.value.spec.deployments
        .find(_.streamletName == streamletName)
        .map(_.volumeMounts)
        .getOrElse(Seq())
    volumeMountsInDeployment should contain(expectedVolumeMount)
  }

  it should "fail if pvc cannot be found" in {
    val volumeName = "volume"
    // Arrange
    val volumeMountFromApi = App.VolumeMountDescriptor(volumeName, "/mnt/data", "ReadWriteMany", Some(""))
    val appCr = crWithVolumeMounts(Seq(volumeMountFromApi))

    // Act
    val updatedCr =
      updateVolumeMounts(
        appCr,
        Map(s"$streamletName.$volumeName" -> "my-pvc-does-not-exist"),
        () => Try(List("my-pvc", "my-other-pvc")))

    // Assert
    updatedCr.isSuccess shouldBe false
  }

  it should "fail if the streamlet cannot be found" in {
    val volumeName = "volume"
    // Arrange
    val volumeMountFromApi = App.VolumeMountDescriptor(volumeName, "/mnt/data", "ReadWriteMany", Some(""))
    val appCr = crWithVolumeMounts(Seq(volumeMountFromApi))

    // Act
    val updatedCr =
      updateVolumeMounts(appCr, Map(s"non-existing.$volumeName" -> "my-pvc"), () => Try(List("my-pvc", "my-other-pvc")))

    // Assert
    updatedCr.isSuccess shouldBe false
  }

  it should "fail if the volume cannot be found" in {
    val volumeName = "volume"
    // Arrange
    val volumeMountFromApi = App.VolumeMountDescriptor(volumeName, "/mnt/data", "ReadWriteMany", Some(""))
    val appCr = crWithVolumeMounts(Seq(volumeMountFromApi))

    // Act
    val updatedCr =
      updateVolumeMounts(
        appCr,
        Map(s"$streamletName.notexisting-volume-name" -> "my-pvc"),
        () => Try(List("my-pvc", "my-other-pvc")))

    // Assert
    updatedCr.isSuccess shouldBe false
  }

  it should "fail if the --volume-mount does not follow <streamletName>.<volumename>" in {
    val volumeName = "volume"
    // Arrange
    val volumeMountFromApi = App.VolumeMountDescriptor(volumeName, "/mnt/data", "ReadWriteMany", Some(""))
    val appCr = crWithVolumeMounts(Seq(volumeMountFromApi))

    // Act
    val updatedCr =
      updateVolumeMounts(
        appCr,
        Map(s"$streamletName$volumeName" -> "my-pvc"),
        () => Try(List("my-pvc", "my-other-pvc")))

    // Assert
    updatedCr.isSuccess shouldBe false
  }

  it should "fail if the --volume-mount does not specify the volume mount that the streamlet needs" in {
    val volumeName = "volume"
    // Arrange
    val volumeMountFromApi = App.VolumeMountDescriptor(volumeName, "/mnt/data", "ReadWriteMany", Some(""))
    val appCr = crWithVolumeMounts(Seq(volumeMountFromApi))

    // Act
    val updatedCr =
      updateVolumeMounts(appCr, Map(), () => Try(List("my-pvc", "my-other-pvc")))

    // Assert
    updatedCr.isSuccess shouldBe false
  }

}

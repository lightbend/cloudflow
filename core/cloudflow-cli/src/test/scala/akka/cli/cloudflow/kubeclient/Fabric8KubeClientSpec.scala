/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.kubeclient

import java.net.{ HttpURLConnection, InetAddress }
import java.util.Collections

import scala.io.Source
import akka.cli.cloudflow.{ CliLogger, Setup }
import akka.cli.cloudflow.models.CRSummary
import io.fabric8.kubernetes.api.model.{ NamespaceBuilder, SecretBuilder }
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest._
import matchers.should._

class Fabric8KubeClientSpec extends AnyFlatSpec with Matchers with BeforeAndAfter {

  implicit val testingLogger = new CliLogger(None)

  val server = new KubernetesServer(false)
  def setupCr() = {
    val swissKnifeCr = Source
      .fromResource("swiss-knife-cr.json")
      .getLines()
      .mkString("\n")

    server.expect.get
      .withPath("/apis/apiextensions.k8s.io/v1/customresourcedefinitions")
      .andReturn(
        HttpURLConnection.HTTP_OK,
        Source
          .fromResource("swiss-knife-crd.json")
          .getLines()
          .mkString("\n"))
      .once

    server.expect.get
      .withPath("/apis/cloudflow.lightbend.com/v1alpha1/cloudflowapplications")
      .andReturn(HttpURLConnection.HTTP_OK, swissKnifeCr)
      .once

    server.expect.get
      .withPath("/apis/cloudflow.lightbend.com/v1alpha1/namespaces/my-ns/cloudflowapplications")
      .andReturn(HttpURLConnection.HTTP_OK, "{}")
      .once

    server.expect.get
      .withPath("/apis/cloudflow.lightbend.com/v1alpha1/namespaces/swiss-knife/cloudflowapplications")
      .andReturn(HttpURLConnection.HTTP_OK, swissKnifeCr)
      .once
  }

  before {
    Setup.init()
    server.before()
  }
  after { server.after() }

  val crSummary = CRSummary("swiss-knife", "swiss-knife", "2.0.11", "2020-10-26T17:26:18Z")

  "The Fabric8KubeClient" should "list applications from the CR" in {
    // Arrange
    setupCr()

    // Act
    val apps =
      new KubeClientFabric8(None, (_) => server.getClient).listCloudflowApps(None)

    // Assert
    apps.isSuccess shouldBe true
    apps.get.size shouldBe 1
    apps.get.head shouldBe crSummary
  }

  it should "not list applications in a different namespace" in {
    // Arrange
    setupCr()

    // Act
    val apps =
      new KubeClientFabric8(None, (_) => server.getClient).listCloudflowApps(Some("my-ns"))

    // Assert
    apps.isSuccess shouldBe true
    apps.get.size shouldBe 0
  }

  it should "show the status of an application from the CR" in {
    // Arrange
    setupCr()

    // Act
    val status =
      new KubeClientFabric8(None, (_) => server.getClient).getCloudflowAppStatus("swiss-knife", "swiss-knife")

    // Assert
    status.isSuccess shouldBe true
    status.get.status shouldBe "Pending"
    status.get.summary shouldBe crSummary
    status.get.endpointsStatuses.size shouldBe 0
    status.get.streamletsStatuses.size shouldBe 10
  }

  it should "create a namespace" in {
    // Arrange
    val client = new KubeClientFabric8(None, (_) => server.getClient)

    val exampleNamespace = new NamespaceBuilder().withNewMetadata().withName("example").endMetadata().build()
    server.expect.post
      .withPath("/api/v1/namespaces")
      .andReturn(HttpURLConnection.HTTP_CREATED, exampleNamespace)
      .once

    server.expect.get
      .withPath("/api/v1/namespaces")
      .andReturn(HttpURLConnection.HTTP_OK, exampleNamespace)
      .once

    // Act
    val res = client.createNamespace("example")

    // Assert
    res.isSuccess shouldBe true
  }

  it should "create docker credentials" in {
    // Arrange
    val client = new KubeClientFabric8(None, (_) => server.getClient)

    val exampleNamespace = new NamespaceBuilder().withNewMetadata().withName("example").endMetadata().build()
    server.expect.get
      .withPath("/api/v1/namespaces")
      .andReturn(HttpURLConnection.HTTP_OK, exampleNamespace)
      .once

    // return an empty answer
    val emptySecretBody = new SecretBuilder().build()
    server.expect.get
      .withPath("/api/v1/namespaces/example/secrets")
      .andReturn(HttpURLConnection.HTTP_OK, emptySecretBody)
      .once

    server.expect.post
      .withPath("/api/v1/namespaces/example/secrets")
      .andReturn(HttpURLConnection.HTTP_OK, emptySecretBody)
      .once

    // Act
    val res = client.createImagePullSecret(
      namespace = "example",
      dockerRegistryURL = "registry.io",
      dockerUsername = "test-username",
      dockerPassword = "test-password")

    // Assert
    res.isSuccess shouldBe true
  }

}

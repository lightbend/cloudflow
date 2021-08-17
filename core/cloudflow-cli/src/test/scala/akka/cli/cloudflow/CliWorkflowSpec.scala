/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow

import java.io.File
import scala.annotation.nowarn
import scala.util.{ Success, Try }
import akka.datap.crd.App
import akka.cli.cloudflow.kubeclient.KubeClient
import akka.cli.cloudflow.models.ApplicationStatus
import akka.cli.microservice.AkkaMicroserviceSpec
import buildinfo.BuildInfo
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest._
import matchers.should._

class CliWorkflowSpec extends AnyFlatSpec with Matchers with TryValues {

  val crFile = new File("./cloudflow-cli/src/test/resources/swiss-knife.json")
  val crFileWithKafkaCluster = new File("./cloudflow-cli/src/test/resources/swiss-knife-with-kafka-cluster.json")
  val testingCRSummary = models.CRSummary("foo", "bar", "0.0.1", "now")

  val listResult = List(testingCRSummary)
  val statusResult = ApplicationStatus(testingCRSummary, "MOCK", List(), List())

  val defaultPvcMounts = List("cloudflow-spark", "cloudflow-flink")

  val defaultProvidedKafkaClusters = Map("default" -> """bootstrap.servers = "localhost:9092"""")

  @nowarn def testingKubeClientFactory(
      protocolVersion: String = Cli.ProtocolVersion,
      sparkVersion: String = Cli.RequiredSparkVersion,
      flinkVersion: String = Cli.RequiredFlinkVersion,
      providedPvcs: List[String] = defaultPvcMounts,
      providedKafkaClusters: Map[String, String] = defaultProvidedKafkaClusters,
      providedApplication: Option[App.Cr] = None,
      providedInputSecret: String = "")(config: Option[File], logger: CliLogger) = {
    new KubeClient {
      def listCloudflowApps(namespace: Option[String]): Try[List[models.CRSummary]] =
        Success(listResult)
      def getCloudflowAppStatus(app: String, namespace: String) =
        Success(statusResult)
      def createImagePullSecret(
          namespace: String,
          dockerRegistryURL: String,
          dockerUsername: String,
          dockerPassword: String): Try[Unit] = Success(())
      def createNamespace(name: String): Try[Unit] = Success(())
      def sparkAppVersion(): Try[String] = Success(sparkVersion)
      def flinkAppVersion(): Try[String] = Success(flinkVersion)
      def createCloudflowApp(spec: App.Spec, namespace: String) = Success("1")
      def uidCloudflowApp(name: String, namespace: String) = Success("1")
      def createMicroservicesApp(
      override def getOperatorProtocolVersion(namespace: Option[String]): Try[String] = Success(protocolVersion)
          cfSpec: App.Spec,
          namespace: String,
          specs: Map[String, Option[AkkaMicroserviceSpec]]): Try[String] =
        Success("1")
      def configureCloudflowApp(
          name: String,
          namespace: String,
          appUid: String,
          appConfig: String,
          loggingContent: Option[String],
          configs: Map[App.Deployment, Map[String, String]]): Try[Unit] = Success(())
      def deleteCloudflowApp(app: String, namespace: String) = Success(())
      def getPvcs(namespace: String) = Success(providedPvcs)
      def getKafkaClusters(namespace: Option[String]) = Success(providedKafkaClusters)
      def readCloudflowApp(name: String, namespace: String): Try[Option[App.Cr]] = Success(providedApplication)
      def updateCloudflowApp(app: App.Cr, namespace: String): Try[App.Cr] = Success(app)
      def getAppInputSecret(name: String, namespace: String): Try[String] = Success(providedInputSecret)
    }
  }

  "The Cli" should "return the current version" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(commands.Version())
    // Assert
    res.isSuccess shouldBe true
    res.success.value.version shouldBe BuildInfo.version
  }

  it should "list mocked CRs" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(commands.List())

    // Assert
    res.isSuccess shouldBe true
    res.success.value.summaries shouldBe listResult
  }

  it should "get a mocked status" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(commands.Status(cloudflowApp = "test"))

    // Assert
    res.isSuccess shouldBe true
    res.success.value.status shouldBe statusResult
  }

  it should "run a mocked deploy" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(commands.Deploy(crFile = crFile))

    // Assert
    res.isSuccess shouldBe true
  }

  it should "fail a mocked deploy if protocol version is incompatible" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory(protocolVersion = "-1"))

    // Act
    val res =
      cli.run(commands.Deploy(crFile = crFile))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("version of kubectl cloudflow is not compatible") shouldBe true
  }

  it should "fail a mocked deploy if spark version is not supported" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory(sparkVersion = "1"))

    // Act
    val res =
      cli.run(commands.Deploy(crFile = crFile))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("spark") shouldBe true
    res.failure.exception.getMessage.contains("required version") shouldBe true
  }

  it should "fail a mocked deploy if flink version is not supported" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory(flinkVersion = "2"))

    // Act
    val res =
      cli.run(commands.Deploy(crFile = crFile))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("flink") shouldBe true
    res.failure.exception.getMessage.contains("required version") shouldBe true
  }

  it should "succeed a mocked deploy if flink is an unmanaged runtime" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory(flinkVersion = "2"))

    // Act
    val res =
      cli.run(commands.Deploy(crFile = crFile, unmanagedRuntimes = Seq("flink")))

    // Assert
    res.isSuccess shouldBe true
  }

  it should "succeed a mocked deploy if provided configuration contains existent streamlets" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(
        commands
          .Deploy(
            crFile = crFile,
            configKeys = Map(
              "cloudflow.streamlets.akka-process.config-parameters" -> "{ configurable-message = value1 }",
              "cloudflow.streamlets.flink-process.config-parameters" -> "{ configurable-message = value2 }")))

    // Assert
    res.isSuccess shouldBe true
  }

  it should "fail a mocked deploy if provided configuration contains inexistent streamlets" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(
        commands
          .Deploy(
            crFile = crFile,
            configKeys = Map("cloudflow.streamlets.my-streamlet.config-parameters" -> "{ key1 = value1 }")))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("my-streamlet") shouldBe true
  }

  it should "fail a mocked deploy if it mention an unexistent pvc" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(commands
        .Deploy(
          crFile = crFile,
          configKeys = Map(
            "cloudflow.streamlets.akka-process.kubernetes.pods.pod.volumes.foo" -> "{ pvc { name = non-existent-mnt } }")))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("non-existent-mnt") shouldBe true
  }

  it should "succeed a mocked deploy if it mention an existent pvc" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory(providedPvcs = defaultPvcMounts :+ "existent-mnt"))

    // Act
    val res =
      cli.run(commands
        .Deploy(
          crFile = crFile,
          configKeys = Map(
            "cloudflow.streamlets.akka-process.kubernetes.pods.pod.volumes.foo" -> "{ pvc { name = existent-mnt } }")))

    // Assert
    res.isSuccess shouldBe true
  }

  it should "fail a mocked deploy if it mention an unexistent topic" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(
        commands
          .Deploy(crFile = crFile, configKeys = Map("cloudflow.topics.not-existent-topic" -> "{ }")))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("not-existent-topic") shouldBe true
  }

  it should "succeed a mocked deploy if it mention a existent topic" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(
        commands
          .Deploy(crFile = crFile, configKeys = Map("cloudflow.topics.akka-pipe" -> "{ }")))

    // Assert
    res.isSuccess shouldBe true
  }

  it should "fail a mocked deploy if the cr contains a non existent kafka cluster" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(
        commands
          .Deploy(crFile = crFileWithKafkaCluster))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("example-kafka-cluster") shouldBe true
  }

  it should "succeed a mocked deploy if the cr contains an existent kafka cluster" in {
    // Arrange
    val cli = new TestingCli(
      testingKubeClientFactory(providedKafkaClusters =
        defaultProvidedKafkaClusters ++
        Map("example-kafka-cluster" -> """bootstrap.servers = "localhost:9092"""")))

    // Act
    val res =
      cli.run(
        commands
          .Deploy(crFile = crFileWithKafkaCluster))

    // Assert
    res.isSuccess shouldBe true
  }

  it should "fail a mocked scale if it contains non-existent streamlet" in {
    // Arrange
    val appCr = Json.mapper.readValue(crFile, classOf[App.Cr])
    val cli = new TestingCli(testingKubeClientFactory(providedApplication = Some(appCr)))

    // Act
    val res =
      cli.run(commands.Scale("skiss-knife", scales = Map("non-existent" -> 5)))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("non-existent") shouldBe true
  }

  it should "succeed a mocked scale if it contains an existent streamlet" in {
    // Arrange
    val appCr = Json.mapper.readValue(crFile, classOf[App.Cr])
    val cli = new TestingCli(testingKubeClientFactory(providedApplication = Some(appCr)))

    // Act
    val res =
      cli.run(commands.Scale("skiss-knife", scales = Map("akka-process" -> 5)))

    // Assert
    res.isSuccess shouldBe true
  }

  it should "fail a mocked configuration if it contains non-existent volume" in {
    // Arrange
    val appCr = Json.mapper.readValue(crFile, classOf[App.Cr])
    val configKey =
      "cloudflow.streamlets.akka-process.kubernetes.pods.pod.volumes.default.pvc.name" -> "non-existent-pvc"
    val cli = new TestingCli(testingKubeClientFactory(providedApplication = Some(appCr)))

    // Act
    val res =
      cli.run(commands.Configure(cloudflowApp = "skiss-knife", configKeys = Map(configKey)))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("non-existent-pvc") shouldBe true
  }

  it should "succeed a mocked configuration if it contains an existent volume" in {
    // Arrange
    val appCr = Json.mapper.readValue(crFile, classOf[App.Cr])
    val configKey =
      "cloudflow.streamlets.akka-process.kubernetes.pods.pod.volumes.default.pvc.name" -> "existent-pvc"
    val cli =
      new TestingCli(
        testingKubeClientFactory(providedPvcs = defaultPvcMounts :+ "existent-pvc", providedApplication = Some(appCr)))

    // Act
    val res =
      cli.run(commands.Configure(cloudflowApp = "skiss-knife", configKeys = Map(configKey)))

    // Assert
    res.isSuccess shouldBe true
  }

  it should "fail a mocked deploy if the version can not be parsed correctly" in {
    // Arrange
    val invalidCrFile = new File("./cloudflow-cli/src/test/resources/invalid-cr1.json")
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(commands.Deploy(crFile = invalidCrFile))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("spec.version is invalid") shouldBe true
  }

  it should "fail a mocked deploy if the library version contains spaces" in {
    // Arrange
    val invalidCrFile = new File("./cloudflow-cli/src/test/resources/invalid-cr2.json")
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(commands.Deploy(crFile = invalidCrFile))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains(" spec.library_version is missing, empty or invalid") shouldBe true
  }

}

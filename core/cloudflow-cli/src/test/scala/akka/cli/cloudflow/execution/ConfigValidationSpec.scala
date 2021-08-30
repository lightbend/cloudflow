/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import akka.cli.cloudflow.CliLogger
import akka.cloudflow.config.CloudflowConfig
import akka.datap.crd.App
import com.typesafe.config.ConfigFactory
import org.scalatest.TryValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConfigValidationSpec extends AnyFlatSpec with Matchers with TryValues {

  def crWithCPDescriptors(cpDescriptors: Seq[App.ConfigParameterDescriptor]) = {
    App.Cr(
      metadata = null,
      spec = App.Spec(
        appId = "",
        appVersion = "",
        deployments = Seq(),
        agentPaths = Map(),
        version = None,
        libraryVersion = None,
        serviceAccount = None,
        streamlets = Seq(
          App.Streamlet(
            name = "my-streamlet",
            descriptor = App.Descriptor(
              attributes = Seq(),
              className = "",
              volumeMounts = Seq(),
              inlets = Seq(),
              labels = Seq(),
              outlets = Seq(),
              runtime = "",
              description = "",
              configParameters = cpDescriptors)))))
  }

  def cloudflowConfigWithCP(configParams: String) = {
    CloudflowConfig.CloudflowRoot(cloudflow = CloudflowConfig.Cloudflow(streamlets =
      Map("my-streamlet" -> CloudflowConfig.Streamlet(configParameters = ConfigFactory.parseString(configParams)))))
  }

  "The Configuration validation" should "validate correct config-parameters" in {
    // Arrange
    val appCr = crWithCPDescriptors(
      Seq(App.ConfigParameterDescriptor(key = "foo", description = "", validationType = "bool", defaultValue = "")))

    val cloudflowConfig = cloudflowConfigWithCP("foo=on")

    val configValidator = new WithConfiguration {
      val logger: CliLogger = new CliLogger(None)
    }

    // Act
    val res = configValidator.validateConfigParameters(appCr, cloudflowConfig)

    // Assert
    res.isSuccess shouldBe true
  }

  it should "validate multiple parameters" in {
    // Arrange
    val appCr = crWithCPDescriptors(
      Seq(
        App.ConfigParameterDescriptor(key = "one", description = "", validationType = "bool", defaultValue = ""),
        App.ConfigParameterDescriptor(key = "two", description = "", validationType = "int32", defaultValue = ""),
        App.ConfigParameterDescriptor(key = "three", description = "", validationType = "double", defaultValue = ""),
        App.ConfigParameterDescriptor(key = "four", description = "", validationType = "string", defaultValue = ""),
        App.ConfigParameterDescriptor(key = "five", description = "", validationType = "duration", defaultValue = ""),
        App.ConfigParameterDescriptor(key = "six", description = "", validationType = "memorysize", defaultValue = "")))

    val cloudflowConfig = cloudflowConfigWithCP("""
        |one = true
        |two = 42
        |three = 1.5
        |four = "baz"
        |five = 1 second
        |six = 512K
        |""".stripMargin)

    val configValidator = new WithConfiguration {
      val logger: CliLogger = new CliLogger(None)
    }

    // Act
    val res = configValidator.validateConfigParameters(appCr, cloudflowConfig)

    // Assert
    res.isSuccess shouldBe true
  }

  it should "validate multiple parameters with missing configuration values" in {
    // Arrange
    val appCr = crWithCPDescriptors(
      Seq(
        App.ConfigParameterDescriptor(key = "one", description = "", validationType = "bool", defaultValue = ""),
        App.ConfigParameterDescriptor(key = "two", description = "", validationType = "int32", defaultValue = "")))

    val cloudflowConfig = cloudflowConfigWithCP("one = true")

    val configValidator = new WithConfiguration {
      val logger: CliLogger = new CliLogger(None)
    }

    // Act
    val res = configValidator.validateConfigParameters(appCr, cloudflowConfig)

    // Assert
    res.isSuccess shouldBe true
  }

  it should "fail if a configuration is not defined" in {
    // Arrange
    val appCr = crWithCPDescriptors(
      Seq(App.ConfigParameterDescriptor(key = "one", description = "", validationType = "bool", defaultValue = "")))

    val cloudflowConfig = cloudflowConfigWithCP("two = true")

    val configValidator = new WithConfiguration {
      val logger: CliLogger = new CliLogger(None)
    }

    // Act
    val res = configValidator.validateConfigParameters(appCr, cloudflowConfig)

    // Assert
    res.isFailure shouldBe true
  }

  it should "fail if the validation type is not recognized" in {
    // Arrange
    val appCr = crWithCPDescriptors(
      Seq(App.ConfigParameterDescriptor(key = "foo", description = "", validationType = "apples", defaultValue = "")))

    val cloudflowConfig = cloudflowConfigWithCP("foo = 3 apples")

    val configValidator = new WithConfiguration {
      val logger: CliLogger = new CliLogger(None)
    }

    // Act
    val res = configValidator.validateConfigParameters(appCr, cloudflowConfig)

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("apples") shouldBe true
  }

  it should "fail if a Boolean is not valid" in {
    // Arrange
    val appCr = crWithCPDescriptors(
      Seq(App.ConfigParameterDescriptor(key = "foo", description = "", validationType = "bool", defaultValue = "")))

    val cloudflowConfig = cloudflowConfigWithCP("foo=bar")

    val configValidator = new WithConfiguration {
      val logger: CliLogger = new CliLogger(None)
    }

    // Act
    val res = configValidator.validateConfigParameters(appCr, cloudflowConfig)

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("foo") shouldBe true
    res.failure.exception.getMessage.contains("boolean") shouldBe true
  }

  it should "fail if a Int is not valid" in {
    // Arrange
    val appCr = crWithCPDescriptors(
      Seq(App.ConfigParameterDescriptor(key = "foo", description = "", validationType = "int32", defaultValue = "")))

    val cloudflowConfig = cloudflowConfigWithCP("foo=1.5")

    val configValidator = new WithConfiguration {
      val logger: CliLogger = new CliLogger(None)
    }

    // Act
    val res = configValidator.validateConfigParameters(appCr, cloudflowConfig)

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("foo") shouldBe true
    res.failure.exception.getMessage.contains("integer") shouldBe true
  }

  it should "fail if a Double is not valid" in {
    // Arrange
    val appCr = crWithCPDescriptors(
      Seq(App.ConfigParameterDescriptor(key = "foo", description = "", validationType = "double", defaultValue = "")))

    val cloudflowConfig = cloudflowConfigWithCP("foo=bar")

    val configValidator = new WithConfiguration {
      val logger: CliLogger = new CliLogger(None)
    }

    // Act
    val res = configValidator.validateConfigParameters(appCr, cloudflowConfig)

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("foo") shouldBe true
    res.failure.exception.getMessage.contains("double") shouldBe true
  }

  it should "fail if a String is not valid" in {
    // Arrange
    val appCr = crWithCPDescriptors(
      Seq(App.ConfigParameterDescriptor(key = "foo", description = "", validationType = "string", defaultValue = "")))

    val cloudflowConfig = cloudflowConfigWithCP("foo= { bar = baz }")

    val configValidator = new WithConfiguration {
      val logger: CliLogger = new CliLogger(None)
    }

    // Act
    val res = configValidator.validateConfigParameters(appCr, cloudflowConfig)

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("foo") shouldBe true
    res.failure.exception.getMessage.contains("string") shouldBe true
  }

  it should "succeed if a String match the validation Pattern" in {
    // Arrange
    val appCr = crWithCPDescriptors(
      Seq(
        App.ConfigParameterDescriptor(
          key = "foo",
          description = "",
          validationType = "string",
          validationPattern = "^[a]*[B]*",
          defaultValue = "")))

    val cloudflowConfig = cloudflowConfigWithCP("foo=aaaaBB")

    val configValidator = new WithConfiguration {
      val logger: CliLogger = new CliLogger(None)
    }

    // Act
    val res = configValidator.validateConfigParameters(appCr, cloudflowConfig)

    // Assert
    res.isSuccess shouldBe true
  }

  it should "fail if a String doesn't match the validation Pattern" in {
    // Arrange
    val appCr = crWithCPDescriptors(
      Seq(
        App.ConfigParameterDescriptor(
          key = "foo",
          description = "",
          validationType = "string",
          validationPattern = "^[a]*[B]*",
          defaultValue = "")))

    val cloudflowConfig = cloudflowConfigWithCP("foo=aaaaBBC")

    val configValidator = new WithConfiguration {
      val logger: CliLogger = new CliLogger(None)
    }

    // Act
    val res = configValidator.validateConfigParameters(appCr, cloudflowConfig)

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("foo") shouldBe true
    res.failure.exception.getMessage.contains("regular expression") shouldBe true
    res.failure.exception.getMessage.contains("^[a]*[B]*") shouldBe true
  }

  it should "fail if a Duration is not valid" in {
    // Arrange
    val appCr = crWithCPDescriptors(
      Seq(App.ConfigParameterDescriptor(key = "foo", description = "", validationType = "duration", defaultValue = "")))

    val cloudflowConfig = cloudflowConfigWithCP("foo= 1 terasecond")

    val configValidator = new WithConfiguration {
      val logger: CliLogger = new CliLogger(None)
    }

    // Act
    val res = configValidator.validateConfigParameters(appCr, cloudflowConfig)

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("foo") shouldBe true
    res.failure.exception.getMessage.contains("duration") shouldBe true
  }

  it should "fail if a Memory Size is not valid" in {
    // Arrange
    val appCr = crWithCPDescriptors(
      Seq(
        App.ConfigParameterDescriptor(key = "foo", description = "", validationType = "memorysize", defaultValue = "")))

    val cloudflowConfig = cloudflowConfigWithCP("foo=512A")

    val configValidator = new WithConfiguration {
      val logger: CliLogger = new CliLogger(None)
    }

    // Act
    val res = configValidator.validateConfigParameters(appCr, cloudflowConfig)

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("foo") shouldBe true
    res.failure.exception.getMessage.contains("memory size") shouldBe true
  }

}

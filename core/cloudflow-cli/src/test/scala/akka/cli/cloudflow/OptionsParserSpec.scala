/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow

import java.io.{ ByteArrayInputStream, File }

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should._
import akka.cli.cloudflow.commands._

class OptionsParserSpec extends AnyFlatSpec with Matchers {

  lazy val crFile = new File("./cloudflow-cli/src/test/resources/swiss-knife.json")
  lazy val confFile1 = new File("./cloudflow-cli/src/test/resources/file1.conf")
  lazy val confFile2 = new File("./cloudflow-cli/src/test/resources/file2.conf")

  "The OptionParser" should "parse generic options" in {
    // Arrange
    val input = Array("version", "--log-level", "info")

    // Act
    val res = OptionsParser(input)

    // Assert
    res.isDefined shouldBe true
    res.get.command.isDefined shouldBe true
    res.get.command.get shouldBe a[Version]
    res.get.command.get.namespace shouldBe None
    res.get.logLevel shouldBe Some("info")
  }

  it should "parse command options" in {
    // Arrange
    val input = Array("list", "-o", "c", "-n", "my-ns")

    // Act
    val res = OptionsParser(input)

    // Assert
    res.isDefined shouldBe true
    res.get.command.isDefined shouldBe true
    res.get.command.get shouldBe a[List]
    res.get.command.get.namespace shouldBe Some("my-ns")
    res.get.command.get.output shouldBe format.Classic
  }

  it should "parse a command argument" in {
    // Arrange
    val input = Array("status", "test")

    // Act
    val res = OptionsParser(input)

    // Assert
    res.isDefined shouldBe true
    res.get.command.isDefined shouldBe true
    res.get.command.get shouldBe a[Status]
    res.get.command.get.asInstanceOf[Status].cloudflowApp shouldBe "test"
  }

  it should "parse multiple command arguments" in {
    // Arrange
    val input = Array(
      "deploy",
      crFile.getAbsolutePath,
      "--conf",
      confFile2.getPath,
      "--conf",
      confFile1.getAbsolutePath,
      "--no-registry-credentials")

    // Act
    val res = OptionsParser(input)

    // Assert
    res.isDefined shouldBe true
    res.get.command.isDefined shouldBe true
    res.get.command.get shouldBe a[Deploy]
    (res.get.command.get.asInstanceOf[Deploy].confs.map(_.getName) should contain)
      .theSameElementsInOrderAs(Seq(confFile2.getName, confFile1.getName))
  }

  it should "parse configurations keys passed as k/v" in {
    // Arrange
    val input = Array(
      "deploy",
      crFile.getAbsolutePath,
      "cloudflow.key1=512",
      "cloudflow.key2=\"SPARK-OUTPUT:\"",
      "--no-registry-credentials")

    // Act
    val res = OptionsParser(input)

    // Assert
    res.isDefined shouldBe true
    res.get.command.isDefined shouldBe true
    res.get.command.get shouldBe a[Deploy]
    val config = res.get.command.get.asInstanceOf[Deploy].aggregatedConfig
    config.isSuccess shouldBe true
    config.get.getInt("cloudflow.key1") shouldBe 512
    config.get.getString("cloudflow.key2") shouldBe "SPARK-OUTPUT:"
  }

  it should "make the correct overrides when using files and k/v config" in {
    // Arrange
    val input = Array(
      "deploy",
      crFile.getAbsolutePath,
      "cloudflow.key3=512",
      "--conf",
      confFile1.getPath,
      "--conf",
      confFile2.getAbsolutePath,
      "--no-registry-credentials")

    // Act
    val res = OptionsParser(input)

    // Assert
    res.isDefined shouldBe true
    res.get.command.isDefined shouldBe true
    res.get.command.get shouldBe a[Deploy]
    val config = res.get.command.get.asInstanceOf[Deploy].aggregatedConfig
    config.isSuccess shouldBe true
    config.get.getString("cloudflow.key1") shouldBe "test1-file1"
    config.get.getString("cloudflow.key2") shouldBe "test2-file2"
    config.get.getInt("cloudflow.key3") shouldBe 512
  }

  it should "correctly parse boolean if the option exists" in {
    // Arrange
    val input = Array("deploy", crFile.getAbsolutePath, "--no-registry-credentials")

    // Act
    val res = OptionsParser(input)

    // Assert
    res.isDefined shouldBe true
    res.get.command.isDefined shouldBe true
    res.get.command.get shouldBe a[Deploy]
    res.get.command.get.asInstanceOf[Deploy].noRegistryCredentials shouldBe true
  }

  it should "read password from the standard in" in {
    // Arrange
    val input = Array(
      "deploy",
      "-u",
      "something",
      crFile.getAbsolutePath,
      "--password-stdin",
      "--conf",
      confFile1.getAbsolutePath)

    // Act
    val in = new ByteArrayInputStream(("abc").getBytes)

    val res = Console.withIn(in) {
      OptionsParser(input)
    }

    // Assert
    res.isDefined shouldBe true
    res.get.command.isDefined shouldBe true
    res.get.command.get shouldBe a[Deploy]
    res.get.command.get.asInstanceOf[Deploy].dockerPassword shouldBe "abc"
    (res.get.command.get.asInstanceOf[Deploy].confs.map(_.getName) should contain)
      .theSameElementsInOrderAs(Seq(confFile1.getName))
  }

  it should "correctly parse map options" in {
    // Arrange
    val input = Array(
      "deploy",
      crFile.getAbsolutePath,
      "--scale",
      "cdr-aggregator1=1,cdr-generator2=2",
      "--volume-mount",
      "my-streamlet1.mount=pvc-name1",
      "--volume-mount",
      "my-streamlet2.mount=pvc-name2",
      "--no-registry-credentials")

    // Act
    val res = OptionsParser(input)

    // Assert
    res.isDefined shouldBe true
    res.get.command.isDefined shouldBe true
    res.get.command.get shouldBe a[Deploy]
    res.get.command.get.asInstanceOf[Deploy].volumeMounts should contain allElementsOf (Map(
      "my-streamlet1.mount" -> "pvc-name1",
      "my-streamlet2.mount" -> "pvc-name2"))
    res.get.command.get.asInstanceOf[Deploy].scales should contain allElementsOf (Map(
      "cdr-aggregator1" -> 1,
      "cdr-generator2" -> 2))
  }

}

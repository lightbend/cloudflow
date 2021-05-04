/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import org.scalatest.{ OptionValues, TryValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ImageSpec extends AnyFlatSpec with Matchers with OptionValues with TryValues {

  "An image" should "fail to parse" in {
    // Arrange
    val images = Seq(
      "!$&%**tests$%&%$&",
      " :test: ",
      "test:",
      ":test:",
      "test:test:test:test",
      "test::test",
      "test:.test",
      "test:-test",
      "http://registry/repo:test",
      "https://registry/repo:test")

    // Act
    val res = images.map(Image(_))

    // Assert
    res.filter(_.isSuccess) shouldBe empty
  }

  it should "fail to parse empty image names" in {
    // Arrange
    val images = Seq("", "  ")

    // Act
    val res = images.map(Image(_))

    // Assert
    res.filter(_.isSuccess) shouldBe empty
  }

  it should "fail to parse strings without the image name" in {
    // Arrange
    val image = "registry/repo/:test"

    // Act
    val res = Image(image)

    // Assert
    res.isSuccess shouldBe false
  }

  it should "fail on images containing only the tag" in {
    // Arrange
    val image = ":test"

    // Act
    val res = Image(image)

    // Assert
    res.isSuccess shouldBe false
  }

  it should "fail on a bad repo name" in {
    // Arrange
    val image = "registry/repo*repo/image:test"

    // Act
    val res = Image(image)

    // Assert
    res.isSuccess shouldBe false
  }

  it should "parse images with tags" in {
    // Arrange
    val image = "registry/repo/image:test"

    // Act
    val res = Image(image)

    // Assert
    res.isSuccess shouldBe true
    res.success.value.registry.value shouldBe "registry"
    res.success.value.repository.value shouldBe "repo"
    res.success.value.image shouldBe "image"
    res.success.value.tag.value shouldBe "test"
  }

  it should "parse images without tags" in {
    // Arrange
    val image = "registry/repo/image"

    // Act
    val res = Image(image)

    // Assert
    res.isSuccess shouldBe true
    res.success.value.registry.value shouldBe "registry"
    res.success.value.repository.value shouldBe "repo"
    res.success.value.image shouldBe "image"
    res.success.value.tag shouldBe None
  }

  it should "parse images with dots and underscores" in {
    // Arrange
    val image = "registry/repo_1.2-45/image_with-1.2_some-dashes"

    // Act
    val res = Image(image)

    // Assert
    res.isSuccess shouldBe true
    res.success.value.registry.value shouldBe "registry"
    res.success.value.repository.value shouldBe "repo_1.2-45"
    res.success.value.image shouldBe "image_with-1.2_some-dashes"
    res.success.value.tag shouldBe None
  }

  it should "parse proper image names" in {
    // Arrange
    val image = "docker-registry-default.purplehat.lightbend.com/lightbend/crazy-rays:386-c66cd02"

    // Act
    val res = Image(image)

    // Assert
    res.isSuccess shouldBe true
    res.success.value.registry.value shouldBe "docker-registry-default.purplehat.lightbend.com"
    res.success.value.repository.value shouldBe "lightbend"
    res.success.value.image shouldBe "crazy-rays"
    res.success.value.tag.value shouldBe "386-c66cd02"
  }

  it should "parse image with explicit registry port" in {
    // Arrange
    val image = "registry.com:1234/repo/image:test"

    // Act
    val res = Image(image)

    // Assert
    res.isSuccess shouldBe true
    res.success.value.registry.value shouldBe "registry.com:1234"
    res.success.value.repository.value shouldBe "repo"
    res.success.value.image shouldBe "image"
    res.success.value.tag.value shouldBe "test"
  }

  it should "parse long image names" in {
    // Arrange
    val image = "some.long.name-with.allowed0912.Registry.com:1234/repo/image:test"

    // Act
    val res = Image(image)

    // Assert
    res.isSuccess shouldBe true
    res.success.value.registry.value shouldBe "some.long.name-with.allowed0912.Registry.com:1234"
    res.success.value.repository.value shouldBe "repo"
    res.success.value.image shouldBe "image"
    res.success.value.tag.value shouldBe "test"
  }

  it should "parse bad DNS registry image names" in {
    // Arrange
    val image = "not_allowed_as_registry_but_ok_as_repo/repo/image:test"

    // Act
    val res = Image(image)

    // Assert
    res.isSuccess shouldBe true
    res.success.value.registry shouldBe None
    res.success.value.repository.value shouldBe "not_allowed_as_registry_but_ok_as_repo/repo"
    res.success.value.image shouldBe "image"
    res.success.value.tag.value shouldBe "test"
  }

  it should "parse image names with many slash" in {
    // Arrange
    val image = "reg/repo1/sub1/sub2/sub3/image:test"

    // Act
    val res = Image(image)

    // Assert
    res.isSuccess shouldBe true
    res.success.value.registry.value shouldBe "reg"
    res.success.value.repository.value shouldBe "repo1/sub1/sub2/sub3"
    res.success.value.image shouldBe "image"
    res.success.value.tag.value shouldBe "test"
  }

  it should "parse images without a repo" in {
    // Arrange
    val image = "image:test"

    // Act
    val res = Image(image)

    // Assert
    res.isSuccess shouldBe true
    res.success.value.registry shouldBe None
    res.success.value.repository shouldBe None
    res.success.value.image shouldBe "image"
    res.success.value.tag.value shouldBe "test"
  }

  it should "parse images with no registry" in {
    // Arrange
    val image = "grafana/grafana:test"

    // Act
    val res = Image(image)

    // Assert
    res.isSuccess shouldBe true
    res.success.value.registry shouldBe None
    res.success.value.repository.value shouldBe "grafana"
    res.success.value.image shouldBe "grafana"
    res.success.value.tag.value shouldBe "test"
  }

  it should "parse versions with sha" in {
    // Arrange
    val image =
      "eu.gcr.io/bubbly-observer-178213/lightbend/rays-sensors@sha256:0840ebe4b207b9ca7eb400e84bf937b64c3809fc275b2e90ba881cbecf56c39a"

    // Act
    val res = Image(image)

    // Assert
    res.isSuccess shouldBe true
    res.success.value.registry.value shouldBe "eu.gcr.io"
    res.success.value.repository.value shouldBe "bubbly-observer-178213/lightbend"
    res.success.value.image shouldBe "rays-sensors"
    res.success.value.tag.value shouldBe "sha256:0840ebe4b207b9ca7eb400e84bf937b64c3809fc275b2e90ba881cbecf56c39a"
  }

}

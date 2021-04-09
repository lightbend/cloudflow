/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.runner

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.config.{ ConfigFactory, ConfigRenderOptions }
import org.scalatest.wordspec._
import org.scalatest.matchers.must._

import scala.collection.immutable
import scala.collection.JavaConverters._

class ConfigSpec extends AnyWordSpec with Matchers {

  private val mapper = new ObjectMapper().registerModule(new DefaultScalaModule())
  private def asJson(s: String) =
    mapper.readTree(ConfigFactory.parseString(s).root().render(ConfigRenderOptions.concise().setJson(true)))

  "a runner.config" should {
    "generate the correct JSON" in {
      // Arrange
      val streamlet = config.Streamlet(
        streamletRef = "reference",
        className = "clazz",
        context = config.StreamletContext(
          appId = "application",
          appVersion = "123",
          config = asJson("{ foo: bar }"),
          volumeMounts = immutable.Seq(config.VolumeMount(name = "foo1", path = "bar", accessMode = "readOnly")),
          portMappings =
            Map("port0" -> config.Topic(id = "id0", cluster = Some("cluster0"), config = asJson("{ bar: baz }")))))

      // Act
      val jsonStr = config.toJson(streamlet)

      // Assert
      val runnerConfig = ConfigFactory.parseString(jsonStr).getConfig("cloudflow.runner.streamlet")
      runnerConfig.getString("streamlet_ref") mustBe "reference"
      runnerConfig.getString("class_name") mustBe "clazz"
      runnerConfig.getString("context.app_id") mustBe "application"
      runnerConfig.getString("context.app_version") mustBe "123"
      runnerConfig.getConfig("context.config").getString("foo") mustBe "bar"
      val volumeMounts = runnerConfig.getConfigList("context.volume_mounts").asScala
      volumeMounts.size mustBe 1
      volumeMounts.head.getString("name") mustBe "foo1"
      volumeMounts.head.getString("path") mustBe "bar"
      volumeMounts.head.getString("access_mode") mustBe "readOnly"
      val port0Mapping = runnerConfig.getConfig("context.port_mappings").getConfig("port0")
      port0Mapping.getString("id") mustBe "id0"
      port0Mapping.getString("cluster") mustBe "cluster0"
      port0Mapping.getConfig("config").getString("bar") mustBe "baz"
    }

    "serialize and deserialize should be idempotent" in {
      // Arrange
      val streamlet = config.Streamlet(
        streamletRef = "reference",
        className = "clazz",
        context = config.StreamletContext(
          appId = "application",
          appVersion = "123",
          config = asJson("{ foo: bar }"),
          volumeMounts = immutable.Seq(config.VolumeMount(name = "foo1", path = "bar", accessMode = "readOnly")),
          portMappings =
            Map("port0" -> config.Topic(id = "id0", cluster = Some("cluster0"), config = asJson("{ bar: baz }")))))

      // Act
      val jsonStr = config.toJson(streamlet)
      val deser = config.fromJson(jsonStr)
      val res = config.toJson(deser)

      // Assert
      jsonStr mustBe res
    }
  }

}

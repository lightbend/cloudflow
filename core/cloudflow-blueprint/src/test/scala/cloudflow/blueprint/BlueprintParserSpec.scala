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

package cloudflow.blueprint

import cloudflow.blueprint.deployment.ApplicationDescriptor
import org.apache.kafka.common.config.TopicConfig
import org.scalatest._

class BlueprintParserSpec extends WordSpec with MustMatchers with EitherValues with OptionValues {
  case class Foo(name: String)
  case class Bar(name: String)

  val separator = java.io.File.separator

  val agentPaths = Map(ApplicationDescriptor.PrometheusAgentKey -> "/app/prometheus/prometheus.jar")

  "A blueprint" should {
    "fail verification if streamlets and streamlet descriptors are empty" in {
      val blueprint = Blueprint.parseString("""blueprint {
          |  streamlets {
          |  }
          |  topics {
          |  }
          |}
          |""".stripMargin, Vector.empty).verify

      blueprint.problems must contain theSameElementsAs Vector(EmptyStreamlets, EmptyStreamletDescriptors)
    }

    "read producers, consumers and extra config" in {
      val blueprint = Blueprint
        .parseString(
          s"""blueprint {
          |  streamlets {
          |    metrics = "sample.Metrics"
          |  }
          |  topics {
          |    metrics {
          |      producers = [metrics.out]
          |      consumers = [validation.in]
          |      extra = "data"
          |    }
          |  }
          |}
          |""".stripMargin,
          Vector.empty
        )
        .verify

      val metricsTopic = blueprint.topics.head
      metricsTopic.id mustBe "metrics"
      metricsTopic.name mustBe "metrics"
      metricsTopic.producers must contain theSameElementsAs Vector("metrics.out")
      metricsTopic.consumers must contain theSameElementsAs Vector("validation.in")
      val config = metricsTopic.kafkaConfig
      config.getString("extra") mustBe "data"
    }

    "overwrite the topic name" in {
      val blueprint = Blueprint
        .parseString(
          """blueprint {
          |  streamlets {
          |  }
          |  topics {
          |    metrics {
          |      topic.name = "ere"
          |    }
          |  }
          |}
          |""".stripMargin,
          Vector.empty
        )
        .verify

      val metricsTopic = blueprint.topics.head
      metricsTopic.id mustBe "metrics"
      metricsTopic.name mustBe "ere"
    }

    "keep topic config" in {
      val blueprint = Blueprint
        .parseString(
          """blueprint {
          |  streamlets {
          |  }
          |  topics {
          |    metrics {
          |      topic {
          |        // See org.apache.kafka.common.config.TopicConfig
          |        // This sections uses Java properties, HOCON units (s, kb) are not available
          |        retention.ms = 3600000
          |        cleanup.policy = compact
          |      }
          |    }
          |  }
          |}
          |""".stripMargin,
          Vector.empty
        )
        .verify

      val metricsTopic = blueprint.topics.head
      metricsTopic.id mustBe "metrics"
      metricsTopic.name mustBe "metrics"
      val topicConfig = cloudflow.blueprint.deployment.Topic.pathAsMap(metricsTopic.kafkaConfig, "topic")
      topicConfig(TopicConfig.RETENTION_MS_CONFIG) mustBe "3600000"
      topicConfig(TopicConfig.CLEANUP_POLICY_CONFIG) mustBe TopicConfig.CLEANUP_POLICY_COMPACT
    }
  }
}

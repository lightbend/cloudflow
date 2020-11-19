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

package cloudflow.akkastream.util.scaladsl

import java.util.concurrent.atomic.AtomicReference

import scala.util.Try

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.kafka.testkit.internal.TestFrameworkInterface

import org.scalatest._
import org.scalatest.Suite
import org.scalatest.concurrent._
import org.testcontainers.{ utility => tcutility }
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.wait.strategy.Wait

abstract class EmbeddedKafkaSpec(system: ActorSystem)
    extends TestKit(system)
    with Suite
    with WordSpecLike
    with MustMatchers
    with ScalaFutures
    with TestFrameworkInterface.Scalatest { this: Suite =>

  private val KafkaPort = 9093

  val kafka = new AtomicReference[KafkaContainer]()

  lazy val kafkaPort = kafka.get().getMappedPort(KafkaPort)

  override def setUp() = {
    val k = new KafkaContainer(tcutility.DockerImageName.parse("confluentinc/cp-kafka:5.4.3"))
      .withExposedPorts(KafkaPort)
      .withEnv("KAFKA_BROKER_ID", "1")
      .withEnv("KAFKA_NUM_PARTITIONS", "53")
      .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
      .withEnv("KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS", "3")
      .waitingFor(
        Wait.forLogMessage(".*Kafka startTimeMs.*\\n", 1)
      )
    k.start()
    kafka.set(k)
  }

  override def cleanUp() =
    Try {
      kafka.get().stop()
      kafka.set(null)
    }
}

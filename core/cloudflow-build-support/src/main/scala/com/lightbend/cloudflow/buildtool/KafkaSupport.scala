package com.lightbend.cloudflow.buildtool

import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import org.testcontainers.{ utility => tcutility }
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.wait.strategy.Wait

import scala.util.Try
import scala.collection.JavaConverters._

object KafkaSupport {

  val KafkaPort = 9093
  val kafka = new AtomicReference[KafkaContainer]()

  def setupKafka(debug: String => Unit) = {

    val cl = Thread.currentThread().getContextClassLoader()
    val kafkaPort =
      try {
        val c = getClass().getClassLoader()
        Thread.currentThread().setContextClassLoader(c)

        val k = new KafkaContainer(tcutility.DockerImageName.parse("confluentinc/cp-kafka:5.4.3"))
          .withExposedPorts(KafkaPort)
          .waitingFor(Wait.forLogMessage(".*Kafka startTimeMs.*\\n", 1))
        k.start()
        kafka.set(k)

        k.getMappedPort(KafkaPort)
      } finally {
        Thread.currentThread().setContextClassLoader(cl)
      }

    debug(s"Setting up Kafka broker in Docker on port: $kafkaPort")

    s"localhost:${kafkaPort}"
  }

  def createTopics(kafkaHost: String, topics: Seq[String])(debug: String => Unit) = {
    import org.apache.kafka.clients.admin.{ AdminClient, AdminClientConfig, NewTopic }
    import scala.collection.JavaConverters._

    var retry = 5
    var adminClient: AdminClient = null

    while (retry > 0) {
      try {
        topics.foreach { topic =>
          debug(s"Kafka Setup: creating topic: $topic")

          if (adminClient == null) {
            adminClient = AdminClient.create(
              Map[String, Object](
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG -> kafkaHost,
                AdminClientConfig.CLIENT_ID_CONFIG -> UUID.randomUUID().toString).asJava)
          }

          val newTopic = new NewTopic(topic, 1, 1.toShort)

          adminClient
            .createTopics(Seq(newTopic).asJava)
            .all
            .get()
        }

        retry = 0
      } catch {
        case _: Throwable =>
          retry -= 1
      } finally {
        if (adminClient != null) {
          adminClient.close(Duration.ofSeconds(30))
        }
      }
    }
  }

  def stopKafka() = Try {
    kafka.get().stop()
    kafka.set(null)
  }

}

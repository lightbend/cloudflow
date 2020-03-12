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

import scala.concurrent._
import scala.concurrent.duration._

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl._

import com.typesafe.config._
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.akkastream.testdata._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._

import net.manub.embeddedkafka._
import org.scalatest.time._

object AkkaStreamletConsumerGroupSpec {
  val kafkaPort = 1234
  val zkPort    = 5678
  val config    = ConfigFactory.parseString(s"""
      akka {
        stdout-loglevel = "OFF"
        loglevel = "OFF"
      }
      cloudflow.kafka.bootstrap-servers = "localhost:$kafkaPort"
      """)
}

import AkkaStreamletConsumerGroupSpec._

class AkkaStreamletConsumerGroupSpec extends EmbeddedKafkaSpec(kafkaPort, zkPort, ActorSystem("test", config)) {

  implicit override val patienceConfig = PatienceConfig(timeout = Span(10, Seconds), interval = Span(20, Millis))

  override def createKafkaConfig: EmbeddedKafkaConfig =
    EmbeddedKafkaConfig(kafkaPort,
                        zooKeeperPort,
                        Map(
                          "broker.id"                        -> "1",
                          "num.partitions"                   -> "53",
                          "offsets.topic.replication.factor" -> "1",
                          "offsets.topic.num.partitions"     -> "3"
                        ))

  "Akka streamlet instances" should {
    "consume from an outlet as a group for the same streamlet" in {
      // Generate some test data
      val dataSize     = 10000
      val data         = List.range(0, dataSize).map(i => Data(i, s"data"))
      val genExecution = Generator.run(data)
      // gen auto-completes, the source is finite.
      genExecution.completed.futureValue

      // all test receivers will write their data to a sink which is probed.
      val probe = akka.testkit.TestProbe()
      val sink  = Sink.actorRef[Data](probe.ref, Completed)

      val instanceIds = List.range(0, 4)
      val executions = instanceIds.map { i =>
        val receiver = new TestReceiver(sink, i)
        // when streamlets are scaled in a cluster, they all run with the same streamlet reference.
        // This is why "receiver" is passed as the streamlet reference for all streamlets
        TestReceiver.run("receiver", receiver)
      }

      // verify the data that the test receiver instances have processed.
      val receivedData = probe.receiveN(dataSize, 15.seconds)

      // verify that all receiver instances together have exactly received all the test data.
      receivedData.size mustBe dataSize
      receivedData.map { case Data(id, _) => id } must contain theSameElementsAs data.map(_.id)

      // verify that all receivers received data. Test receivers set 'name' to the instance id it was created with.
      receivedData.map { case Data(_, name) => name.toInt }.distinct.sorted must contain theSameElementsAs instanceIds
      Future.sequence(executions.map(_.stop())).futureValue
    }

    "consume from an outlet grouped by streamlet reference" in {
      // Generate some test data
      val dataSize     = 5000
      val data         = List.range(0, dataSize).map(i => Data(i, s"data"))
      val genExecution = Generator.run(data)
      // gen auto-completes, the source is finite.
      genExecution.completed.futureValue

      // all test receivers will write their data to a sink which is probed.
      val probe = akka.testkit.TestProbe()
      val sink  = Sink.actorRef[Data](probe.ref, Completed)

      val numberOfUniqueStreamlets = 2
      val instanceIds              = List.range(0, numberOfUniqueStreamlets)
      val executions = instanceIds.map { i =>
        val receiver = new TestReceiver(sink, i)

        // unique streamlet references, receivers should all receive all data.
        TestReceiver.run(s"receiver-$i", receiver)
      }
      // every receiver should receive all data, using a unique group.
      val expectedSize = dataSize * numberOfUniqueStreamlets
      // verify the data that the test receiver instances have processed.
      val receivedData = probe.receiveN(expectedSize, 15.seconds)

      // verify that all receiver instances together have exactly received all the test data.
      receivedData.size mustBe expectedSize

      // verify that all receivers received data. Test receivers set 'name' to the instance id it was created with.
      receivedData.map { case Data(_, name) => name.toInt }.distinct.sorted must contain theSameElementsAs instanceIds
      Future.sequence(executions.map(_.stop())).futureValue
    }

    //TODO add consume from outlet using different streamlets
  }

  object Completed

  val appId      = "my-app"
  val appVersion = "abc"

  object Generator {
    val StreamletClass = "Generator"
    val Out            = "out"
    val StreamletRef   = "gen"

    def run(testData: List[Data]): StreamletExecution = {
      val gen     = new Generator(testData)
      val context = new AkkaStreamletContextImpl(definition(StreamletRef), system)
      gen.setContext(context)
      gen.run(context)
    }

    def definition(streamletRef: String) = StreamletDefinition(
      appId = appId,
      appVersion = appVersion,
      streamletRef = StreamletRef,
      streamletClass = StreamletClass,
      portMapping = List(
        ConnectedPort("out", SavepointPath(appId, streamletRef, Out))
      ),
      volumeMounts = List.empty[VolumeMount],
      config = config
    )
  }

  class Generator(testData: List[Data]) extends AkkaStreamlet {
    import Generator._
    val out                  = AvroOutlet[Data](Out)
    final override val shape = StreamletShape.withOutlets(out)
    override final def createLogic = new RunnableGraphStreamletLogic() {
      def runnableGraph = Source(testData).to(plainSink(out))
    }
  }

  object TestReceiver {
    val streamletClass = "TestReceiver"
    val out            = "out"

    def run(streamletRef: String, receiver: TestReceiver): StreamletExecution = {
      val context = new AkkaStreamletContextImpl(definition(streamletRef), system)
      receiver.setContext(context)
      receiver.run(context)
    }

    def definition(streamletRef: String) = StreamletDefinition(
      appId = appId,
      appVersion = appVersion,
      streamletRef = streamletRef,
      streamletClass = "TestReceiver",
      portMapping = List(
        ConnectedPort("in", SavepointPath(appId, Generator.StreamletRef, Generator.Out))
      ),
      volumeMounts = List.empty[VolumeMount],
      config = config
    )
  }

  class TestReceiver(sink: Sink[Data, NotUsed], instance: Int) extends AkkaStreamlet {
    val in                   = AvroInlet[Data]("in")
    final override val shape = StreamletShape.withInlets(in)

    val flow = Flow[Data].map(_.copy(name = s"$instance"))

    override final def createLogic = new RunnableGraphStreamletLogic() {
      def runnableGraph = plainSource(in, Earliest).via(flow).to(sink)
    }
  }
}

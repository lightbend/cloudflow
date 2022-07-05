/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
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

package cloudflow.akkastream

import scala.concurrent._
import scala.util._

import akka._
import akka.actor.ActorSystem
import akka.kafka._
import akka.kafka.ConsumerMessage._
import akka.kafka.scaladsl._
import akka.stream._
import akka.stream.scaladsl._

import org.apache.kafka.clients.producer.{ Callback, RecordMetadata }

import cloudflow.streamlets._

final class KafkaSinkRef[T](
    system: ActorSystem,
    outlet: CodecOutlet[T],
    bootstrapServers: String,
    topic: Topic,
    killSwitch: SharedKillSwitch,
    completionPromise: Promise[Dun])
    extends WritableSinkRef[T]
    with ProducerHelper {

  private val producerSettings: ProducerSettings[Array[Byte], Array[Byte]] =
    producerSettings(topic, bootstrapServers)(system)
  private val producer = producerSettings.createKafkaProducer()

  def sink: Sink[(T, Committable), NotUsed] = {
    system.log.info(s"Creating sink for topic: $topic")

    Flow[(T, Committable)]
      .map {
        case (value, offset) =>
          ProducerMessage.Message(producerRecord(outlet, topic, value), offset)
      }
      .via(Producer.flexiFlow(producerSettings.withProducer(producer)))
      .via(handleTermination)
      .to(Sink.ignore)
      .mapMaterializedValue(_ => NotUsed)
  }

  private def handleTermination[I]: Flow[I, I, NotUsed] =
    Flow[I]
      .via(killSwitch.flow)
      .alsoTo(Sink.onComplete {
        case Success(_) =>
          system.log.error(s"Stream has completed. Shutting down streamlet...")
          completionPromise.success(Dun)
        case Failure(e) =>
          system.log.error(e, "Stream has failed. Shutting down streamlet...")
          completionPromise.failure(e)
      })

  def write(value: T): Future[T] = {
    val promise = Promise[T]()

    producer.send(producerRecord(outlet, topic, value), new Callback() {
      def onCompletion(metadata: RecordMetadata, exception: Exception) {
        if (exception == null) promise.success(value)
        else promise.failure(exception)
      }
    })

    promise.future
  }
}

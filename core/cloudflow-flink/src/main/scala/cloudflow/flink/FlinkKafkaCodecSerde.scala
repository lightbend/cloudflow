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

package cloudflow.flink

import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.consumer.ConsumerRecord

import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.connectors.kafka._

import cloudflow.streamlets.{ CodecInlet, CodecOutlet }

private[flink] class FlinkKafkaCodecSerializationSchema[T: TypeInformation](outlet: CodecOutlet[T], topic: String)
    extends KafkaSerializationSchema[T] {
  override def serialize(value: T, timestamp: java.lang.Long): ProducerRecord[Array[Byte], Array[Byte]] =
    new ProducerRecord(topic, outlet.codec.encode(value))
}

private[flink] class FlinkKafkaCodecDeserializationSchema[T: TypeInformation](inlet: CodecInlet[T]) extends KafkaDeserializationSchema[T] {
  override def deserialize(record: ConsumerRecord[Array[Byte], Array[Byte]]): T = inlet.codec.decode(record.value)
  override def isEndOfStream(value: T): Boolean                                 = false
  override def getProducedType: TypeInformation[T]                              = implicitly[TypeInformation[T]]
}

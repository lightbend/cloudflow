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

package cloudflow.flink

import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.connectors.kafka._
import cloudflow.streamlets.{ CodecOutlet, RoundRobinPartitioner }
import org.apache.flink.api.java.typeutils.TypeExtractor

private[flink] class FlinkKafkaCodecSerializationSchema[T: TypeInformation](outlet: CodecOutlet[T], topic: String)
    extends KafkaSerializationSchema[T] {
  override def serialize(value: T, timestamp: java.lang.Long): ProducerRecord[Array[Byte], Array[Byte]] =
    outlet.partitioner match {
      case RoundRobinPartitioner => // round robin - no key
        new ProducerRecord(topic, outlet.codec.encode(value))
      case _ => // use the key
        new ProducerRecord(topic, outlet.partitioner(value).getBytes(), outlet.codec.encode(value))
    }
}

private[flink] class FlinkKafkaCodecDeserializationSchema() extends KafkaDeserializationSchema[Array[Byte]] {
  override def deserialize(record: ConsumerRecord[Array[Byte], Array[Byte]]): Array[Byte] = record.value
  override def isEndOfStream(value: Array[Byte]): Boolean                                 = false
  override def getProducedType: TypeInformation[Array[Byte]]                              = TypeExtractor.getForClass(classOf[Array[Byte]])
}

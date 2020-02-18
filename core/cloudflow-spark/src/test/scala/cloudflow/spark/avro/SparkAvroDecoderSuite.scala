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

package cloudflow.spark.avro

import org.apache.spark.sql.Row
import org.scalatest.{ Matchers, WordSpec }
import cloudflow.streamlets.avro.AvroCodec
import cloudflow.streamlets.Codec

import cloudflow.spark.sql.SQLImplicits._

class SparkAvroDecoderSuite extends WordSpec with Matchers {

  val simpleCodec: Codec[Simple]   = new AvroCodec(Simple.SCHEMA$)
  val complexCodec: Codec[Complex] = new AvroCodec(Complex.SCHEMA$)

  "SparkAvroDecoder" should {
    "decode a simple case class" in {

      val sample  = Simple("sphere")
      val encoded = simpleCodec.encode(sample)

      val decoder = new SparkAvroDecoder[Simple](Simple.SCHEMA$.toString)
      val result  = decoder.decode(encoded)
      result.getAs[String](0) should be(sample.name)
    }

    "decode a complex case class" in {

      val complex = Complex(Simple("room"), 101, 0.01, 0.001f)
      val encoded = complexCodec.encode(complex)

      val decoder = new SparkAvroDecoder[Complex](Complex.SCHEMA$.toString)
      val result  = decoder.decode(encoded)
      result.getAs[Row](0).getAs[String](0) should be(complex.simple.name)
      result.getAs[Int](1) should be(complex.count)
      result.getAs[Double](2) should be(complex.range)
      result.getAs[Float](3) should be(complex.error)
    }

  }

}

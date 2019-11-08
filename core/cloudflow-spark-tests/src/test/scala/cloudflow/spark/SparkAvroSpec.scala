/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
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

package cloudflow.spark

import scala.collection.immutable.Seq

import org.apache.spark.sql.Dataset

import cloudflow.spark.avro._
import cloudflow.spark.testkit._

class SparkAvroSpec extends SparkScalaTestSupport {

  "SparkAvroEncoder" should {
    "preserve List structure in objects with nested collections" in {

      val socks = Product("123456789", "Socks", "Warm in winter", Seq("clothing", "sock", "socks"), Seq(Sku("1", "sock-1", Some(1), Some(10)), Sku("2", "sock-2", Some(2), Some(20))))

      import sqlCtx.implicits._

      val asProductDS: Dataset[Product] = (Seq(socks)).toDF.as[Product]

      val encodedProduct = new SparkAvroEncoder[Product](Product.SCHEMA$.toString).encode(asProductDS)
      val decodedProduct = new SparkAvroDecoder[Product](Product.SCHEMA$.toString).decode(encodedProduct.first)

      decodedProduct.getAs[Seq[String]](3).size mustBe 3
      decodedProduct.getAs[Seq[Sku]](4).size mustBe 2
    }
  }
}

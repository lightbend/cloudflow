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

package cloudflow.spark.testkit

import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec._
import org.scalatest.matchers.must._

@deprecated("Use contrib-sbt-spark library instead, see https://github.com/lightbend/cloudflow-contrib", "2.2.0")
trait SparkScalaTestSupport extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  val session: SparkSession = SparkSession
    .builder()
    .master("local[*]")
    .config("spark.driver.bindAddress", "127.0.0.1")
    .appName("test")
    .getOrCreate()
  session.sparkContext.setLogLevel("WARN")

  implicit lazy val sqlCtx = session.sqlContext

  override def afterAll: Unit =
    session.stop()
}

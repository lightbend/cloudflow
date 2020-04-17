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

package cloudflow.streamlets

import com.typesafe.config.ConfigFactory
import org.scalatest.{ MustMatchers, OptionValues, TryValues, WordSpec }

class StreamletDefinitionSpec extends WordSpec with MustMatchers with TryValues with OptionValues {

  "A valid StreamletConfig" should {
    val config          = ConfigFactory.load("config-map-sample.json")
    val streamletConfig = StreamletDefinition.read(config).get

    "the loaded instances must contain class, instance and port information" in {
      val expectedStreamlet = ("sensor-data", "cloudflow.examples.sensordata.SensorDataIngress$")
      streamletConfig.streamletRef must be(expectedStreamlet._1)
      streamletConfig.streamletClass must be(expectedStreamlet._2)
    }

    "a loaded instance must have port configuration" in {
      val ports = streamletConfig.portMapping
      val expectedPorts = Map(
        "accepted" -> SavepointPath("appId", "sensor-data", "accepted", ConfigFactory.empty(), None),
        "rejected" -> SavepointPath("appId", "sensor-data", "rejected", ConfigFactory.empty(), None)
      )
      ports.foreach(connectedPort ⇒ expectedPorts(connectedPort.port) must be(connectedPort.savepointPath))
    }

    "a loaded instance must have its own configuration" in {
      val config = streamletConfig.config
      config.getInt("cloudflow.internal.server.container-port") must be(2049)
    }

    "a loaded instance must have the common configuration" in {
      config.getString("cloudflow.common.attribute") must be("value")
      config.getString("cloudflow.kafka.bootstrap-servers") must be("cloudflow-kafka.lightbend:9092")
    }

    "a loaded instance must not have runner configuration" in {
      val config = streamletConfig.config
      config.hasPath("runner") must be(false)
    }
  }
}

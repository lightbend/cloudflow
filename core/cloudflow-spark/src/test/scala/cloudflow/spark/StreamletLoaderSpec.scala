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

package cloudflow.spark

import com.typesafe.config._
import net.ceedubs.ficus.Ficus._
import org.scalatest._

import cloudflow.streamlets._

class StreamletLoaderSpec extends WordSpec with StreamletLoader with MustMatchers with TryValues {
  val streamletObjectImpl                          = ClassOps.nameOf(ToUpperObject)
  val streamletClassImpl                           = ClassOps.nameOf[ToUpperClass]
  val streamletClassImplWithArgs                   = ClassOps.nameOf[ToUpperCaseParamClass]
  val streamletClassWithCompanionObjectImpl        = ClassOps.nameOf[SparkStreamletWithCompanionObject]
  val streamletClassWithArgsAndCompanionObjectImpl = ClassOps.nameOf[SparkStreamletWithArgsAndCompanionObject]
  val streamletAsCompanionObjectImpl               = ClassOps.nameOf[SparkStreamletAsCompanionObject]
  val streamletRef                                 = "1"
  val emptyConfig                                  = ConfigFactory.empty()

  def flowConfig(appId: String, appVersion: String, streamletClass: String): StreamletDefinition =
    ConfigFactory.parseString(s"""
    streamlet-config = {
      class_name = $streamletClass
      streamlet_ref = $streamletRef
      context = {
        "app_id" = $appId,
        "app_version": $appVersion,
        "config" : {
          "fake-config-value" : "bla"
        }
        "port_mappings": {
          "in ": {
            "app_id": "appId",
            "streamlet_ref": "to-metrics",
            "id": "metrics-in",
            "config": {}
          },
          "out": {
            "app_id": "appId",
            "streamlet_ref": "to-metrics",
            "id": "metrics-out",
            "config": {}
          }
        },
        "volume_mounts": [
          {
            "name":"test-mount",
            "path":"/mnt/test",
            "access_mode":"ReadWriteMany"
          }
        ]
      }
    }
  """).as[StreamletDefinition]("streamlet-config")

  // val context = StreamletDeploymentContext("appid", "unknown", List.empty[ConnectedPorts], emptyConfig)
  val appId      = "tipsy-rhinoceros-42"
  val appVersion = "unknown"

  "StreamletLoader" should {

    "successfully load a streamlet from an object implementation" in {
      val loaded = loadStreamlet(flowConfig(appId, appVersion, streamletObjectImpl)).success.value
      loaded.streamlet mustBe a[SparkStreamlet]
    }

    "successfully load a streamlet from a class with companion object implementation" in {
      val loaded = loadStreamlet(flowConfig(appId, appVersion, streamletClassWithCompanionObjectImpl)).success.value
      loaded.streamlet mustBe a[SparkStreamlet]
    }

    "return a failure with a streamlet from a class with args and companion object implementation" in {
      val failure = loadStreamlet(flowConfig(appId, appVersion, streamletClassWithArgsAndCompanionObjectImpl)).failure
      failure.exception mustBe a[InvalidStreamletClass]
    }

    "successfully load a streamlet from a companion object implementation" in {
      val loaded = loadStreamlet(flowConfig(appId, appVersion, streamletAsCompanionObjectImpl)).success.value
      loaded.streamlet mustBe a[SparkStreamlet]
    }

    "successfully load a streamlet from a class implementation" in {
      val loaded = loadStreamlet(flowConfig(appId, appVersion, streamletClassImpl)).success.value
      loaded.streamlet mustBe a[SparkStreamlet]
    }

    "create a new streamlet instance on each call" in {
      for {
        LoadedStreamlet(streamlet1, _) <- loadStreamlet(flowConfig(appId, appVersion, streamletObjectImpl))
        LoadedStreamlet(streamlet2, _) <- loadStreamlet(flowConfig(appId, appVersion, streamletObjectImpl))
      } yield (streamlet1 mustNot be(theSameInstanceAs(streamlet2)))
    }

    val checkContextForStreamlet: String => Unit = { streamletImpl =>
      val LoadedStreamlet(_, conf) = loadStreamlet(flowConfig(appId, appVersion, streamletImpl)).success.value
      conf.config.getString("fake-config-value") mustBe "bla"
    }

    "load the context from the definition when streamlet is an object" in {
      checkContextForStreamlet(streamletObjectImpl)
    }

    "load the context from the definition when streamlet is a class" in {
      checkContextForStreamlet(streamletClassImpl)
    }

    "return a failure if the streamlet class does not have a default no-args constructor" in {
      val failure = loadStreamlet(flowConfig(appId, appVersion, streamletClassImplWithArgs)).failure
      failure.exception mustBe a[NoArgsConstructorExpectedException]
    }

    "return a failure if the streamlet class cannot be found on the classpath" in {
      loadStreamlet(flowConfig(appId, appVersion, "notExisting")).failure.exception mustBe a[StreamletClassNotFound]
    }

    "return a failure if the streamlet class configured is not a Streamlet" in {
      loadStreamlet(flowConfig(appId, appVersion, "java.lang.String")).failure.exception mustBe a[InvalidStreamletClass]
    }

    "return a failure if the streamlet is a trait with no class or object" in {
      loadStreamlet(flowConfig(appId, appVersion, "TrivialSparklet")).failure.exception mustBe a[StreamletClassNotFound]
    }

    "return a failure if we have a trait that extends a class that extends Sparklet" in {
      loadStreamlet(flowConfig(appId, appVersion, "MySparklet")).failure.exception mustBe a[StreamletClassNotFound]
    }

  }
}

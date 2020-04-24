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

import java.io.{ File, InvalidObjectException }
import java.util.UUID.randomUUID

import scala.util.{ Failure, Try }

import com.typesafe.config._
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers._
import spray.json._

case class StreamletDefinition(appId: String,
                               appVersion: String,
                               streamletRef: String,
                               streamletClass: String,
                               portMapping: List[ConnectedPort],
                               volumeMounts: List[VolumeMount],
                               config: Config) {

  private val portNameToTopicMap: Map[String, Topic] = {
    portMapping.map {
      case ConnectedPort(port, savepointPath) ⇒ port -> savepointPath
    }.toMap
  }

  def resolveTopic(port: StreamletPort): Option[Topic] = resolveTopic(port.name)
  def resolveTopic(port: String): Option[Topic]        = portNameToTopicMap.get(port)

}

object Topic {
  //TODO  used for testing, remove
  def apply(appId: String, streamletRef: String, outlet: String): Topic =
    Topic(appId, streamletRef, s"$appId.$streamletRef.$outlet", ConfigFactory.empty(), None)
}

/**
 * The path to a savepoint.
 */
final case class Topic(
    appId: String,
    streamletRef: String,
    name: String,
    config: Config,
    bootstrapServers: Option[String]
) {

  def groupId[T](readingStreamletRef: String, inlet: CodecInlet[T]) = {
    val base = s"$appId.$readingStreamletRef.${inlet.name}"
    if (inlet.hasUniqueGroupId) s"${base}.${randomUUID.toString}"
    else base
  }

  import scala.collection.JavaConverters._
  def kafkaProducerProperties: Map[String, String] =
    if (config.hasPath("producer-config")) {
      config
        .getConfig("producer-config")
        .entrySet()
        .asScala
        .map(entry => entry.getKey -> entry.getValue.unwrapped().toString)
        .toMap
    } else Map.empty[String, String]

  def kafkaConsumerProperties: Map[String, String] =
    if (config.hasPath("consumer-config")) {
      config
        .getConfig("consumer-config")
        .entrySet()
        .asScala
        .map(entry => entry.getKey -> entry.getValue.unwrapped().toString)
        .toMap
    } else Map.empty[String, String]
}

/**
 * Mapping between the port name and the savepoint path
 */
case class ConnectedPort(port: String, savepointPath: Topic)

object StreamletDefinition {
  implicit val contextDataReader: ValueReader[StreamletContextData] =
    ValueReader.relative { config ⇒
      val json = config.root().render(ConfigRenderOptions.concise())

      StreamletContextDataJsonSupport
        .fromJson(json)
        .recoverWith {
          case cause ⇒
            Failure(new ConfigException.BadValue("context", s"invalid context: ${cause.getMessage}", cause))
        }
        .get
    }
  implicit val configReader: ValueReader[StreamletDefinition] = ValueReader.relative { config ⇒
    val streamletRef         = config.as[String]("streamlet_ref")
    val streamletContextData = config.as[StreamletContextData]("context")
    StreamletDefinition(
      appId = streamletContextData.appId,
      appVersion = streamletContextData.appVersion,
      streamletRef = streamletRef,
      streamletClass = config.as[String]("class_name"),
      streamletContextData.connectedPorts,
      streamletContextData.volumeMounts.getOrElse(List()),
      streamletContextData.config
    )
  }

  val StreamletRootPath = "cloudflow.runner.streamlets"
  def read(config: Config, rootPath: String = StreamletRootPath): Try[StreamletDefinition] = Try {
    config.as[Vector[StreamletDefinition]](rootPath).head
  }

  // checkpoint folder set up
  def makeStateCheckpointDir(path: String): Try[String] = Try {
    // state checkpoint directory on mount
    val checkpointDir = new File(path)
    checkpointDir.getAbsolutePath
  }

}

case class StreamletContextData(
    appId: String,
    appVersion: String,
    connectedPorts: List[ConnectedPort],
    volumeMounts: Option[List[VolumeMount]] = None,
    config: Config
)

/**
 * Helper object for creating an instance of StreamletContextData from JSON.
 */
object StreamletContextDataJsonSupport extends DefaultJsonProtocol {

  protected implicit val configFormat = new JsonFormat[Config] {
    def write(config: Config): JsValue = config.root().render(ConfigRenderOptions.concise()).parseJson
    def read(json: JsValue): Config    = ConfigFactory.parseString(json.toString)
  }
  implicit val savepointPathFormat = jsonFormat(Topic.apply, "app_id", "streamlet_ref", "name", "config", "bootstrap_servers")
  protected implicit val accessModeFormat = new JsonFormat[AccessMode] {
    val jsReadWriteMany = JsString("ReadWriteMany")
    val jsReadOnlyMany  = JsString("ReadOnlyMany")
    def write(accessMode: AccessMode): JsValue = accessMode match {
      case ReadWriteMany ⇒ jsReadWriteMany
      case ReadOnlyMany  ⇒ jsReadOnlyMany
    }
    def read(json: JsValue): AccessMode = json match {
      case `jsReadWriteMany` ⇒ ReadWriteMany
      case `jsReadOnlyMany`  ⇒ ReadOnlyMany
      case x                 ⇒ throw new InvalidObjectException(s"'$x' is not a valid Access Mode.")
    }
  }

  protected implicit val volumeMountFormat    = jsonFormat(VolumeMount.apply _, "name", "path", "access_mode")
  protected implicit val connectedPortsFormat = jsonFormat(ConnectedPort, "port", "savepoint_path")
  protected implicit val contextDataFormat =
    jsonFormat(StreamletContextData, "app_id", "app_version", "connected_ports", "volume_mounts", "config")

  /**
   * Converts a json String, that is expected to contain one streamlet
   * context json object, into a StreamletContext.
   *
   * @param json the json to deserialize
   */
  def fromJson(json: String): Try[StreamletContextData] =
    Try(json.parseJson.convertTo[StreamletContextData])

  /**
   * Converts a context into a json string.
   */
  def toJson(context: StreamletContextData): String =
    context.toJson.compactPrint
}

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

package cloudflow.runner

import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.{ JsonCreator, JsonInclude, JsonProperty }
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

import scala.collection.immutable
import com.fasterxml.jackson.databind.{ JsonDeserializer, JsonNode, ObjectMapper }
import com.fasterxml.jackson.module.scala.DefaultScalaModule

object config {

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonInclude(Include.NON_NULL)
  @JsonCreator
  case class Topic(
      @JsonProperty("id")
      id: String,
      @JsonProperty("cluster")
      cluster: Option[String],
      @JsonProperty("config")
      config: JsonNode)

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonInclude(Include.NON_NULL)
  @JsonCreator
  case class VolumeMount(
      @JsonProperty("name")
      name: String,
      @JsonProperty("path")
      path: String,
      @JsonProperty("access_mode")
      accessMode: String)

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonInclude(Include.NON_NULL)
  @JsonCreator
  case class StreamletContext(
      @JsonProperty("app_id")
      appId: String,
      @JsonProperty("app_version")
      appVersion: String,
      @JsonProperty("config")
      config: JsonNode,
      @JsonProperty("volume_mounts")
      volumeMounts: immutable.Seq[VolumeMount] = immutable.Seq.empty,
      @JsonProperty("port_mappings")
      portMappings: Map[String, Topic] = Map.empty)

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonInclude(Include.NON_NULL)
  @JsonCreator
  case class Streamlet(
      @JsonProperty("class_name")
      className: String,
      @JsonProperty("streamlet_ref")
      streamletRef: String,
      @JsonProperty("context")
      context: StreamletContext)

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonInclude(Include.NON_NULL)
  @JsonCreator
  case class Runner(
      @JsonProperty("streamlet")
      streamlet: Streamlet)

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonInclude(Include.NON_NULL)
  @JsonCreator
  case class Cloudflow(
      @JsonProperty("runner")
      runner: Runner)

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonInclude(Include.NON_NULL)
  @JsonCreator
  case class CloudflowRoot(
      @JsonProperty("cloudflow")
      cloudflow: Cloudflow)

  private val mapper = new ObjectMapper().registerModule(new DefaultScalaModule())

  def toJson(streamlet: Streamlet): String =
    mapper.writeValueAsString(CloudflowRoot(cloudflow = Cloudflow(runner = Runner(streamlet = streamlet))))
  def fromJson(s: String): Streamlet = mapper.readValue(s, classOf[CloudflowRoot]).cloudflow.runner.streamlet

}

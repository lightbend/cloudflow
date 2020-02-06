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

package cloudflow.sbt

import java.nio.charset.StandardCharsets._
import java.util.Base64
import java.util.zip.Deflater
import java.io.ByteArrayOutputStream

import com.typesafe.config._

import sbt._
import sbt.Keys._
import sbtdocker.DockerKeys._
import com.typesafe.sbt.packager.archetypes._
import spray.json._

import cloudflow.sbt.CloudflowKeys._
import cloudflow.blueprint.StreamletDescriptorFormat._
import cloudflow.blueprint.StreamletDescriptor

/**
 * Base class for all Cloudflow runtime plugins for multi-image use case. Contains some
 * methods which are reused across the runtime plugins, `CloudflowFlinkPlugin`,
 * `CloudflowAkkaPlugin` and `CloudflowSparkPlugin`.
 */
abstract class CloudflowBasePlugin extends AutoPlugin {
  final val AppHome = "${app_home}"
  final val AppTargetDir: String = "/app"
  final val appTargetSubdir: String ⇒ String = dir ⇒ s"$AppTargetDir/$dir"
  final val AppJarsDir: String = "app-jars"
  final val DepJarsDir: String = "dep-jars"
  final val OptAppDir = "/opt/cloudflow/"
  final val scalaVersion = "2.12"

  // NOTE !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
  // The UID and GID of the `jboss` user is used in different parts of Cloudflow
  // If you change this, you have to make sure that all references to this value are changed
  // - fsGroups on streamlet pods uses the GID to make volumes readable
  val UserInImage = "185" // default non-root user in the spark image
  val userAsOwner: String ⇒ String = usr ⇒ s"$usr:cloudflow"
  val StreamletDescriptorsLabelName = "com.lightbend.cloudflow.streamlet-descriptors"

  override def requires = CommonSettingsAndTasksPlugin && StreamletScannerPlugin &&
    JavaAppPackaging && sbtdocker.DockerPlugin

  private[sbt] val verifyDockerRegistry = Def.task {
    cloudflowDockerRegistry.value.getOrElse(throw DockerRegistryNotSet)
  }

  private[sbt] val checkUncommittedChanges = Def.task {
    val log = streams.value.log
    if (cloudflowBuildNumber.value.hasUncommittedChanges) {
      log.warn(s"You have uncommitted changes in ${thisProjectRef.value.project}. Please commit all changes before publishing to guarantee a repeatable and traceable build.")
    }
  }

  private[sbt] def makeStreamletDescriptorsLabelValue(streamletDescriptorsJson: JsValue) = {
    // create a root object with the array
    val streamletDescriptorsJsonStr =
      JsObject("streamlet-descriptors" -> streamletDescriptorsJson)
        .compactPrint

    val compressed = zlibCompression(streamletDescriptorsJsonStr.getBytes(UTF_8))
    Base64.getEncoder.encodeToString(compressed)
  }

  private[sbt] val showResultOfBuild = Def.task {
    val log = streams.value.log
    val imagePushed = (imageNames in docker).value.head // assuming we only build a single image!

    log.info(" ") // if you remove the space, the empty line will be auto-removed by SBT somehow...
    log.info("Successfully built the following image:")
    log.info(" ")
    log.info(s"  $imagePushed")
    log.info(" ")
    log.info("Before you can deploy this image to a Kubernetes cluster you will")
    log.info("need to push it to a docker registry that is reachable from the cluster.")
    log.info(" ")
  }

  private[sbt] val showResultOfBuildAndPublish = Def.task {
    val log = streams.value.log
    val imagePushed = (imageNames in docker).value.head // assuming we only build a single image!

    log.info(" ") // if you remove the space, the empty line will be auto-removed by SBT somehow...
    log.info("Successfully built and published the following image:")
    log.info(s"  $imagePushed")
  }

  private[sbt] def zlibCompression(raw: Array[Byte]): Array[Byte] = {
    val deflater = new Deflater()
    val compressed = new ByteArrayOutputStream(0)
    deflater.setInput(raw)
    deflater.finish()
    val buffer = new Array[Byte](1024)
    while (!deflater.finished()) {
      val len = deflater.deflate(buffer)
      compressed.write(buffer, 0, len)
    }
    deflater.end()
    compressed.toByteArray()
  }

  private[sbt] def buildStreamletDescriptors(
      detectedStreamlets: Map[String, Config]): Def.Initialize[Task[Iterable[StreamletDescriptor]]] = Def.task {
    val detectedStreamletDescriptors = detectedStreamlets.map {
      case (_, configDescriptor) ⇒
        val jsonString = configDescriptor.root().render(ConfigRenderOptions.concise())
        jsonString.parseJson.convertTo[cloudflow.blueprint.StreamletDescriptor]
    }
    detectedStreamletDescriptors
  }
}

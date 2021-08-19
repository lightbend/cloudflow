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

package cloudflow.sbt

import scala.util._

import cloudflow.extractor.DescriptorExtractor
import sbt._
import sbt.Keys._
import com.typesafe.config._

import cloudflow.extractor.ExtractResult
import cloudflow.sbt.CloudflowKeys._

object StreamletScannerPlugin extends AutoPlugin {
  override def requires = CommonSettingsAndTasksPlugin

  override def projectSettings =
    Seq(
      cloudflowStreamletDescriptors := scanForStreamlets.value,
      cloudflowApplicationClasspath := applicationClasspath.value,
      cloudflowApplicationClasspathByProject := (ThisProject / name).value -> applicationClasspath.value,
      streamletDescriptorsByProject := streamletsByProject.value,
      mappings in (Compile, packageBin) += {
        streamletDescriptorsFile.value -> "streamlet-descriptors.conf"
      })

  private def applicationClasspath: Def.Initialize[Task[Array[URL]]] = Def.task {
    toClasspathUrls((ThisProject / Compile / fullClasspath).value)
  }

  private def streamletsByProject: Def.Initialize[Task[(String, ExtractResult)]] = Def.task {
    val descriptors = cloudflowStreamletDescriptors.value
    (ThisProject / name).value -> descriptors
  }

  private def scanForStreamlets: Def.Initialize[Task[ExtractResult]] = Def.task {
    val log = streams.value.log

    DescriptorExtractor.scan(
      DescriptorExtractor
        .ScanConfiguration(projectId = (ThisProject / name).value, classpathUrls = applicationClasspath.value))
  }

  private def streamletDescriptorsFile: Def.Initialize[Task[File]] = Def.task {
    val file = (classDirectory in Compile).value / "streamlet-descriptors.conf"

    val config = DescriptorExtractor.resolve(
      DescriptorExtractor.ResolveConfiguration(dockerImageName =
        (ThisProject / cloudflowDockerImageName).value.map(_.asTaggedName).getOrElse("placeholder")),
      scanForStreamlets.value)
    IO.write(file, config.root().render(ConfigRenderOptions.defaults.setOriginComments(false).setComments(false)))
    file
  }

  private def toClasspathUrls(attributedFiles: Seq[Attributed[File]]): Array[URL] =
    attributedFiles.files.map(_.toURI.toURL).toArray
}

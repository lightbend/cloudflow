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

import sbt._
import sbt.Keys._
import com.typesafe.config._

import cloudflow.sbt.CloudflowKeys._

object StreamletScannerPlugin extends AutoPlugin {
  override def requires = CommonSettingsAndTasksPlugin

  override def projectSettings = Seq(
    cloudflowStreamletDescriptors := scanForStreamlets.value,
    cloudflowApplicationClasspath := applicationClasspath.value,
    cloudflowApplicationClasspathByProject := (ThisProject / name).value -> applicationClasspath.value,
    streamletDescriptorsByProject := streamletsByProject.value,
    mappings in (Compile, packageBin) += {
      streamletDescriptorsFile.value -> "streamlet-descriptors.conf"
    }
  )

  private def applicationClasspath: Def.Initialize[Task[Array[URL]]] = Def.task {
    toClasspathUrls((fullClasspath in Compile).value)
  }

  private def streamletsByProject: Def.Initialize[Task[(String, Map[String, Config])]] = Def.task {
    val descriptors = cloudflowStreamletDescriptors.value
    (ThisProject / name).value -> descriptors
  }

  private def scanForStreamlets: Def.Initialize[Task[Map[String, Config]]] = Def.task {
    val log       = streams.value.log
    val projectId = (ThisProject / name).value

    val classpath   = applicationClasspath.value
    val parent      = ClassLoader.getSystemClassLoader.getParent
    val classLoader = new java.net.URLClassLoader(classpath, parent)

    val streamletDescriptors = StreamletScanner.scanForStreamletDescriptors(classLoader, projectId)

    streamletDescriptors.flatMap {
      case (streamletClassName, Success(descriptor)) =>
        log.info(s"Streamlet '$streamletClassName' found")
        Some(streamletClassName -> descriptor)

      case (_, Failure(error)) =>
        log.error(error.getMessage)
        None
    }
  }

  private def streamletDescriptorsFile: Def.Initialize[Task[File]] = Def.task {
    val file = (classDirectory in Compile).value / "streamlet-descriptors.conf"
    val scan = scanForStreamlets.value
    val config = scan.foldLeft(ConfigFactory.empty) {
      case (acc, (name, conf)) =>
        // TODO cleanup
        acc.withValue(
          s""""$name"""",
          conf
            .root()
            .withValue(
              "image",
              cloudflowDockerImageName.value
                .map(din => ConfigValueFactory.fromAnyRef(din.asTaggedName))
                .getOrElse(ConfigValueFactory.fromAnyRef("placeholder"))
            )
        )
    }

    IO.write(file, config.root().render(ConfigRenderOptions.defaults.setOriginComments(false).setComments(false)))
    file
  }

  private def toClasspathUrls(attributedFiles: Seq[Attributed[File]]): Array[URL] =
    attributedFiles.files.map(_.toURI.toURL).toArray
}

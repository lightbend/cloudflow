/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.extractor

import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }

import java.io.File
import java.net.{ URL, URLClassLoader }
import scala.util.{ Failure, Success }

object DescriptorExtractor {

  final case class ScanConfiguration(projectId: String, classpathUrls: Array[URL])
  final case class ResolveConfiguration(dockerImageName: String)

  def scan(config: ScanConfiguration): Map[String, Config] = {
    val cl = new URLClassLoader(config.classpathUrls, ClassLoader.getSystemClassLoader.getParent)

    val streamletDescriptors = StreamletScanner.scanForStreamletDescriptors(cl, config.projectId)

    streamletDescriptors.flatMap {
      case (streamletClassName, Success(descriptor)) =>
        Some(streamletClassName -> descriptor)

      case (_, Failure(error)) =>
        None
    }
  }

  def resolve(config: ResolveConfiguration, streamlets: Map[String, Config]) = {
    val descriptor = streamlets.foldLeft(ConfigFactory.empty) {
      case (acc, (name, conf)) =>
        acc.withValue(
          s""""$name"""",
          conf
            .root()
            .withValue("image", ConfigValueFactory.fromAnyRef(config.dockerImageName)))
    }

    descriptor
  }

}

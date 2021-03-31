package akka.cloudflow

import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }

import java.io.File
import java.net.URLClassLoader
import scala.util.{ Failure, Success }

object DescriptorGenerator {

  final case class Configuration(projectId: String, dockerImageName: String, classpath: Array[String]) {

    def classpathUrls() = classpath.map(s => new File(s).toURI.toURL)
  }

  def apply(config: Configuration): Config = {
    val cl = new URLClassLoader(config.classpathUrls(), ClassLoader.getSystemClassLoader.getParent)

    val streamletDescriptors = StreamletScanner.scanForStreamletDescriptors(cl, config.projectId)

    val result = streamletDescriptors.flatMap {
      case (streamletClassName, Success(descriptor)) =>
        Some(streamletClassName -> descriptor)

      case (_, Failure(error)) =>
        None
    }

    val descriptor = result.foldLeft(ConfigFactory.empty) {
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

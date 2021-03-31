package akka.cloudflow

import com.typesafe.config.{ ConfigFactory, ConfigRenderOptions }

import java.net.URL
import scala.collection.JavaConverters._

object Main {

  def main(args: Array[String]): Unit = {
    if (args.size != 1) {
      Console.err.println("Need exactly one argument that is the configuration file")
      System.exit(1)
    } else {
      val config = ConfigFactory.load(args(0))
      val scanConfig = DescriptorGenerator.ScanConfiguration(
        projectId = config.getString("projectId"),
        classpathUrls = config.getStringList("classpath").asScala.map(new URL(_)).toArray)
      val resolveConfig =
        DescriptorGenerator.ResolveConfiguration(dockerImageName = config.getString("dockerImageName"))

      val result =
        DescriptorGenerator.resolve(resolveConfig, DescriptorGenerator.scan(scanConfig))
      Console.out.println(
        result.root().render(ConfigRenderOptions.defaults.setOriginComments(false).setComments(false)))
      System.exit(0)
    }
  }

}

package akka.cloudflow

import akka.cloudflow.DescriptorGenerator.Configuration
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}

import scala.jdk.CollectionConverters._

object Main {

  def main(args: Array[String]): Unit = {
    if (args.size != 1) {
      Console.err.println("Need exactly one argument that is the configuration file")
      System.exit(1)
    } else {
      val config = ConfigFactory.load(args(0))
      val c = Configuration(
        projectId = config.getString("projectId"),
        dockerImageName = config.getString("dockerImageName"),
        classpath = config.getStringList("classpath").asScala.toArray)

      Console.out.println(DescriptorGenerator(c).root().render(ConfigRenderOptions.defaults.setOriginComments(false).setComments(false)))
      System.exit(0)
    }
  }

}

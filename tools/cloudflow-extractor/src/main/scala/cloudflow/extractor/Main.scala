/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.extractor

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
      val scanConfig = DescriptorExtractor.ScanConfiguration(
        projectId = config.getString("projectId"),
        classpathUrls = config.getStringList("classpath").asScala.map(new URL(_)).toArray)
      val resolveConfig =
        DescriptorExtractor.ResolveConfiguration(dockerImageName = config.getString("dockerImageName"))

      val result =
        DescriptorExtractor.resolve(resolveConfig, DescriptorExtractor.scan(scanConfig))
      Console.out.println(
        result.root().render(ConfigRenderOptions.defaults.setOriginComments(false).setComments(false)))
      System.exit(0)
    }
  }

}

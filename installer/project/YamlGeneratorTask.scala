import sbt._
import sbt.Keys._
import sbt.util.FileFunction

import scala.sys.process._

object YamlGeneratorTask {
  val task = Def.task {
    val yaml = baseDirectory.value / "yaml"

    val cachedTask = FileFunction.cached(
      streams.value.cacheDirectory / "yaml"
    ) { (in: Set[File]) =>
      println("Generating Yaml files")

      val generatedFiles = (yaml / "kustomize") ** "*.yaml"

      val errorBuilder = new collection.mutable.StringBuilder()
      val errorLogger  = ProcessLogger(line => {}, line => errorBuilder.++=(line + "\n"))

      if ((s"make -C ${yaml.getAbsolutePath()} generate-yaml" ! errorLogger) != 0)
        sys.error("Yaml generation failed\n" + errorBuilder.toString)

      generatedFiles.get().toSet
    }

    val sourceFiles = ((yaml / "config") ** "*" +++
          (yaml / "resources") ** "*").get :+
          (yaml / "definitions.mk") :+
          (yaml / "Makefile")

    cachedTask(sourceFiles.toSet).toSeq
  }

}

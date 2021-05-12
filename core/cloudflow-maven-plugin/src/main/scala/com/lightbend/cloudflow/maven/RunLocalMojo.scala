package com.lightbend.cloudflow.maven

import buildinfo.BuildInfo
import cloudflow.cr.Generator
import com.github.mdr.ascii.graph.Graph
import com.github.mdr.ascii.layout.GraphLayout
import com.typesafe.config.{ Config, ConfigFactory, ConfigValue }
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.{ AbstractMojo, BuildPluginManager }
import org.apache.maven.plugins.annotations._
import org.apache.maven.project.MavenProject

import spray.json._
import cloudflow.blueprint.deployment._
import cloudflow.blueprint.deployment.CloudflowCRFormat._

import java.io.File
import java.net.URLEncoder
import java.nio.file.{ Path, Paths }
import scala.collection.JavaConverters._

@Mojo(
  name = "run-local",
  aggregator = false,
  requiresDependencyResolution = ResolutionScope.COMPILE,
  requiresDependencyCollection = ResolutionScope.COMPILE,
  defaultPhase = LifecyclePhase.PACKAGE)
class RunLocalMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  var mavenProject: MavenProject = _

  @Parameter(defaultValue = "${session}", required = true, readonly = true)
  var mavenSession: MavenSession = _

  @Component
  var pluginManager: BuildPluginManager = _

//  def runLocal(topLevel: MavenProject) = {
//    val cpByProject = allApplicationClasspathByProject.value
//
//    val crFile = new File(topLevel.getBuild.getDirectory, topLevel.getName + ".json")
//
//    if (crFile.exists()) {
//
//      val appDesc = FileUtil
//        .readLines(crFile)
//        .mkString("\n")
//        .parseJson
//        .convertTo[CloudflowCR]
//
//      val res = getAppLayout(resolveConnections(appDesc.spec))
//    } else {
//      // TODO -> avoid pushing images?
//      getLog.error("Application CR not found, run first build-app")
//    }
//
//    val projects = streamletDescriptorsByProject.keys
//
//    // load local config
//    val localConfig = LocalConfig.load(configFile)
//    val baseDebugPort = initialDebugPort.value
//
//    val (tempDir, configDir) = createDirs("cloudflow-local-run")
//    val descriptorByProject = projects.map { pid =>
//      val streamletClasses = streamletDescriptorsByProject(pid).keys.toSet
//      pid -> streamletFilterByClass(appDescriptor, streamletClasses)
//    }
//
//    val runtimeDescriptorByProject = getDescriptorsOrFail {
//      descriptorByProject.map {
//        case (pid, projectDescriptor) =>
//          pid -> scaffoldRuntime(pid, projectDescriptor, localConfig, tempDir, configDir, runLocalLog4jConfigFile.value)
//      }
//    }
//
//    val topics = appDescriptor.deployments
//      .flatMap { deployment =>
//        deployment.portMappings.values.map(_.name)
//      }
//      .distinct
//      .sorted
//    val kafkaHost = {
//      val host = (ThisBuild / runLocalKafka).value.getOrElse(setupKafka())
//      createTopics(host, topics)
//      host
//    }
//
//    println(getAppLayout(resolveConnections(appDescriptor)))
//    printInfo(runtimeDescriptorByProject, tempDir.toFile, topics, localConfig.message)
//
//    val processes = runtimeDescriptorByProject.zipWithIndex.map {
//      case ((pid, rd), debugPortOffset) =>
//        val classpath = cpByProject(pid)
//        val loggingPatchedClasspath = prepareLoggingInClasspath(classpath, logDependencies)
//        runPipelineJVM(
//          pid,
//          rd.appDescriptorFile,
//          loggingPatchedClasspath,
//          rd.outputFile,
//          rd.logConfig,
//          rd.localConfPath,
//          kafkaHost,
//          remoteDebugRunLocal.value,
//          baseDebugPort + debugPortOffset,
//          runLocalJavaOptions.value)
//    }
//
//    println(s"Running ${appDescriptor.appId}  \nTo terminate, press [ENTER]\n")
//
//    try {
//      sbt.internal.util.SimpleReader.readLine("")
//      logger.info("Attempting to terminate local application")
//      processes.foreach(_.destroy())
//    } catch {
//      case ex: Throwable =>
//        logger.warn("Stopping process failed.")
//        ex.printStackTrace()
//    } finally {
//      stopKafka()
//    }
//  }

  def execute(): Unit = {
    val topLevel = mavenSession.getTopLevelProject
    val projectId = topLevel.getName
    val version = topLevel.getVersion

    val allProjects = mavenSession.getAllProjects.asScala

    if (allProjects.last == mavenProject) {

      val blueprintFile = CloudflowAggregator.getBlueprint(allProjects, getLog())
      val streamletsPerProject = CloudflowAggregator.getStreamlets(allProjects, getLog())

      val streamlets = streamletsPerProject.foldLeft(Map.empty[String, Config]) { case (acc, (_, v)) => acc ++ v }

      val blueprintStr = FileUtil.readLines(blueprintFile.get).mkString("")
      val blueprint = ConfigFactory.parseString(blueprintStr).getObject("blueprint.streamlets").asScala

      val placeholderImages = blueprint.map { case (k, _) => k -> "placeholder" }.toMap

      try {
        val cr = Generator.generate(
          projectId = projectId,
          version = version,
          blueprintStr = blueprintStr,
          streamlets = streamlets,
          dockerImages = placeholderImages)

        getLog.info("Going to run ... ")
      } catch {
        case ex: Throwable =>
          getLog().error(ex.getMessage)
      }

      CloudflowAggregator.getCR(topLevel) match {
        case Some(c) =>
        case None    =>
          // TODO -> make this work even with placeholder images ...
          getLog().error("Run cloudflow:build-app first")
      }
    }
  }

}

/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.cloudflow.buildtool

import java.nio.file._
import java.io._
import com.typesafe.config.{ Config, ConfigFactory }
import cloudflow.blueprint.deployment._
import cloudflow.blueprint.deployment.ApplicationDescriptorJsonFormat._
import spray.json._

import scala.util._

object Scaffold {

  def getDescriptorsOrFail[T](descriptors: Iterable[(T, Try[RuntimeDescriptor])])(
      error: String => Unit): Iterable[(T, RuntimeDescriptor)] = {
    descriptors
      .collect {
        case (_, Failure(ex)) =>
          error(s"Determining runtime descriptors failed: ${ex.getMessage}")
          ex
      }
      .foreach { ex =>
        throw ex
      }
    descriptors.collect { case (pid, Success(runtimeDescriptor)) => (pid, runtimeDescriptor) }
  }

  def scaffoldRuntime(
      projectId: String,
      descriptor: ApplicationDescriptor,
      localConfig: LocalConfig,
      targetDir: Path,
      configDir: Path,
      log4jConfig: Try[Path],
      write: (File, String) => Unit): Try[RuntimeDescriptor] = {
    for {
      appDescriptor <- prepareApplicationDescriptor(descriptor, localConfig.content, targetDir)
      outputFile <- createOutputFile(targetDir, projectId)
      logFile <- log4jConfig
      appDescriptorFile <- prepareApplicationFile(appDescriptor, write)
    } yield {
      RuntimeDescriptor(appDescriptor.appId, appDescriptor, appDescriptorFile, outputFile, logFile, localConfig.path)
    }
  }

  def streamletFilterByClass(
      appDescriptor: ApplicationDescriptor,
      streamletClasses: Set[String]): ApplicationDescriptor = {
    val streamletInstances =
      appDescriptor.streamlets.filter(streamlet => streamletClasses(streamlet.descriptor.className))
    val deploymentDescriptor =
      appDescriptor.deployments.filter(streamletDeployment => streamletClasses(streamletDeployment.className))
    appDescriptor.copy(streamlets = streamletInstances, deployments = deploymentDescriptor)
  }

  def prepareApplicationFile(applicationDescriptor: ApplicationDescriptor, write: (File, String) => Unit): Try[Path] =
    Try {
      val localApplicationFile = Files.createTempFile("local-runner", ".json")
      val contents = applicationDescriptor.toJson.prettyPrint
      write(localApplicationFile.toFile, contents)
      localApplicationFile
    }

  def createOutputFile(workDir: Path, projectId: String): Try[File] = Try {
    val localFile = workDir.resolve(projectId + "-local.log").toFile
    if (!localFile.exists()) {
      Files.createFile(workDir.resolve(projectId + "-local.log")).toFile
    }
    localFile
  }

  def createDirs(prefix: String): (Path, Path) = {
    val workDir = Files.createTempDirectory(prefix)
    val configDir = workDir.resolve("config")
    val configDirFile = configDir.toFile
    if (!configDirFile.exists()) {
      configDirFile.mkdirs()
    }
    (workDir, configDir)
  }

  def prepareApplicationDescriptor(
      applicationDescriptor: ApplicationDescriptor,
      config: Config,
      tempDir: Path): Try[ApplicationDescriptor] = {
    val updatedStreamlets = tryOverrideVolumeMounts(applicationDescriptor.streamlets, config, tempDir)
    updatedStreamlets.map(streamlets => applicationDescriptor.copy(streamlets = streamlets))
  }

  case class Exceptions(values: Seq[Throwable]) extends Exception(values.map(ex => ex.getMessage).mkString(", "))
  def tryOverrideVolumeMounts(
      streamlets: Vector[StreamletInstance],
      localConf: Config,
      localStorageDir: Path): Try[Vector[StreamletInstance]] = {

    val updatedStreamlets = streamlets.map { streamlet =>
      val streamletName = streamlet.name
      val confPath = s"cloudflow.streamlets.$streamletName.volume-mounts"
      val streamletVolumeConf =
        if (localConf.hasPath(confPath)) localConf.getConfig(confPath) else ConfigFactory.empty()
      val volumeMounts = streamlet.descriptor.volumeMounts
      val localVolumeMounts = volumeMounts.map { volumeMount =>
        val tryLocalPath = Try {
          streamletVolumeConf.getString(volumeMount.name)
        }.recoverWith {
          case _ =>
            Try {
              val path = localStorageDir.resolve(volumeMount.name).toFile
              path.mkdirs() // create the temp dir
              path.getAbsolutePath
            }
        }
        tryLocalPath.map(localPath => volumeMount.copy(path = localPath))
      }
      foldExceptions(localVolumeMounts).map(volumeMounts =>
        streamlet.copy(descriptor = streamlet.descriptor.copy(volumeMounts = volumeMounts.toVector)))
    }
    foldExceptions(updatedStreamlets).map(_.toVector)
  }

  def foldExceptions[T](collection: Seq[Try[T]]): Try[Seq[T]] = {
    val zero: Try[Seq[T]] = Success(Seq())
    collection.foldLeft(zero) {
      case (Success(seq), Success(elem))          => Success(elem +: seq)
      case (Success(_), Failure(f))               => Failure(Exceptions(Seq(f)))
      case (Failure(f), Success(_))               => Failure(f)
      case (Failure(Exceptions(exs)), Failure(f)) => Failure(Exceptions(f +: exs))
      case (Failure(f1), Failure(f2))             => Failure(Exceptions(Seq(f1, f2)))
    }
  }

}

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

package cloudflow.runner

import scala.util.{ Failure, Success, Try }
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.io.Source
import java.nio.file.{ Files, Paths }

import org.slf4j.LoggerFactory
import com.typesafe.config.{ Config, ConfigFactory }
import cloudflow.streamlets._

/**
 * Runner for cluster deployments. Assumes Linux-style paths!
 */
object Runner extends RunnerConfigResolver with StreamletLoader {
  lazy val log = LoggerFactory.getLogger(getClass.getName)

  sys.props.get("os.name") match {
    case Some(os) if os.startsWith("Win") =>
      log.error("cloudflow.runner.Runner is NOT compatible with Windows!!")
    case Some(os) => log.info(s"Runner running on $os")
    case None     => log.warn("""sys.props.get("os.name") returned None!""")
  }

  val PVCMountPath: String = "/mnt/spark/storage"
  val DownwardApiVolumeMountPath: String = "/mnt/downward-api-volume"

  def main(args: Array[String]): Unit = run()

  private def run(): Unit = {

    val result: Try[(Config, LoadedStreamlet)] = for {
      runnerConfig <- makeConfig
      loadedStreamlet <- loadStreamlet(runnerConfig)
    } yield (runnerConfig, loadedStreamlet)

    result match {
      case Success((runnerConfig, loadedStreamlet)) =>
        val withStorageConfig = addStorageConfig(runnerConfig, PVCMountPath)
        val withPodRuntimeConfig = addPodRuntimeConfig(withStorageConfig, DownwardApiVolumeMountPath)

        val streamletExecution = loadedStreamlet.streamlet.run(withPodRuntimeConfig)
        loadedStreamlet.streamlet.logStartRunnerMessage(formatBuildInfo)

        // the runner waits for the execution to complete
        // In normal circumstances it will run forever for streaming data source unless
        // being stopped forcibly or any of the queries faces an exception
        try {
          Await.result(streamletExecution.completed, Duration.Inf)
          shutdown(loadedStreamlet)
        } catch {
          case ex @ ExceptionAcc(exceptions @ _) =>
            shutdown(loadedStreamlet, Some(ex))
          case ex: Throwable =>
            shutdown(loadedStreamlet, Some(ex))
        }
      case Failure(ex) => throw new Exception(ex)
    }
  }

  private def shutdown(loadedStreamlet: LoadedStreamlet, maybeException: Option[Throwable] = None) = {
    // we created this file when the pod started running (see AkkaStreamlet#run)
    Files.deleteIfExists(Paths.get(s"/tmp/${loadedStreamlet.config.streamletRef}.txt"))
    maybeException match {
      case Some(ex) =>
        log.error("A fatal error has occurred. The streamlet is going to shutdown", ex)
        System.exit(-1)
      case None =>
        log.info("Streamlet terminating without failure")
        System.exit(0)
    }
  }

  private def formatBuildInfo: String = {
    import BuildInfo._

    s"""
    |Name          : $name
    |Version       : $version
    |Scala Version : $scalaVersion
    |sbt Version   : $sbtVersion
    |Build Time    : $buildTime
    |Build User    : $buildUser
    """.stripMargin
  }

  def addStorageConfig(config: Config, pvcVolumeMountPath: String): Config = {
    val storageConfig = ConfigFactory.parseString(s"""$StorageMountPathKey:"$pvcVolumeMountPath"""")
    config.withFallback(storageConfig)
  }

  def addPodRuntimeConfig(config: Config, downwardApiVolumeMountPath: String): Config = {
    val (name, namespace, uid) = getPodMetadata(downwardApiVolumeMountPath)
    val podRuntimeConfig = ConfigFactory.parseString(s"""
                                                              |cloudflow.runner.pod: {
                                                              |  $MetadataName:"$name"
                                                              |  $MetadataNamespace:"$namespace"
                                                              |  $MetadataUid:"$uid"
                                                              |}
                                                              |""".stripMargin)
    config.withFallback(podRuntimeConfig)
  }
  def getPodMetadata(downwardApiVolumeMountPath: String): (String, String, String) = {
    val name = readDownwardApi(downwardApiVolumeMountPath, MetadataName)
    val namespace = readDownwardApi(downwardApiVolumeMountPath, MetadataNamespace)
    val uid = readDownwardApi(downwardApiVolumeMountPath, MetadataUid)
    (name, namespace, uid)
  }

  private def readDownwardApi(downwardApiVolumeMountPath: String, filename: String): String = {
    val path = s"$downwardApiVolumeMountPath/$filename"
    Try(Source.fromFile(path).getLines().mkString) match {
      case Success(contents) => contents
      case Failure(ex) =>
        throw new Exception(
          s"An error occurred while attempting to access the downward API volume mount with path '$path'",
          ex)
    }
  }

  val StorageMountPathKey = "storage.mountPath"
  val MetadataName = "metadata.name"
  val MetadataNamespace = "metadata.namespace"
  val MetadataUid = "metadata.uid"

}

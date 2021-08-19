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

import scala.util.Try
import sbt._
import com.typesafe.config._

import cloudflow.blueprint.deployment.ApplicationDescriptor
import cloudflow.blueprint.StreamletDescriptor
import cloudflow.extractor.ExtractResult

case class DockerImageName(name: String, tag: String) {
  def asTaggedName: String = s"$name:$tag"
}

trait CloudflowSettingKeys {

  object SchemaCodeGenerator {
    sealed trait Language
    case object Java extends Language
    case object Scala extends Language
  }

  object SchemaFormat {
    sealed trait Format
    case object Avro extends Format
    case object Proto extends Format
  }

  val cloudflowDockerBaseImage = settingKey[String]("The base Docker image to use for Cloudflow images.")
  @deprecated("Use 'cloudflowDockerBaseImage' instead")
  val cloudflowFlinkBaseImage = settingKey[Option[String]]("The base image for Cloudflow Flink plugin")
  @deprecated("Use 'cloudflowDockerBaseImage' instead")
  val cloudflowSparkBaseImage = settingKey[Option[String]]("The base image for Cloudflow Spark plugin")
  @deprecated("Use 'cloudflowDockerBaseImage' instead")
  val cloudflowAkkaBaseImage = settingKey[Option[String]]("The base image for Cloudflow Akka plugin")
  val cloudflowVersion =
    settingKey[String]("The version of Cloudflow, for development purposes, change it at your own risk")
  val blueprint = settingKey[Option[String]]("The path to the blueprint file to use in this Cloudflow application.")
  val schemaCodeGenerator =
    settingKey[SchemaCodeGenerator.Language]("The language to generate data model schemas into.")
  val schemaPaths = settingKey[Map[SchemaFormat.Format, String]]("A Map of paths to your data model schemas.")
  val runLocalKafka = settingKey[Option[String]]("The external Kafka to use with the local runner Sandbox.")
  val runLocalConfigFile =
    settingKey[Option[String]]("The HOCON configuration file to use with the local runner Sandbox.")
  val runLocalLog4jConfigFile = settingKey[Option[String]](
    s"The path to the log4j configuration file to use with the local runner Sandbox, if omitted, ${CloudflowApplicationPlugin.DefaultLocalLog4jConfigFile} is read from plugin classpath.")

  val runLocalJavaOptions =
    settingKey[Option[String]](s"Java options that will be added to JVMs that are forked by runLocal")

  val ownerInDockerImage =
    settingKey[String](
      "The user as owner in the resulting docker image, which can be used as chown in docker copy instructions.")
  val initialDebugPort =
    settingKey[Int]("Initial port number for debugging in runLocal. It will be increased by one for each Streamlet")
  val remoteDebugRunLocal = settingKey[Boolean]("Enable/Disable remote debugging for streamlets in runLocal")
  val appGraphSavePath = settingKey[File]("The path where an application graph visualization should be saved.")
}

trait CloudflowTaskKeys {

  val cloudflowDockerImageName = taskKey[Option[DockerImageName]]("The name of the Docker image to publish.")
  val cloudflowDockerRegistry =
    taskKey[Option[String]]("The hostname and (optional) port of the Docker registry to use.")
  val cloudflowDockerRepository = taskKey[Option[String]]("The image repository name on the Docker registry.")
  val baseDockerInstructions =
    taskKey[Seq[sbtdocker.Instruction]](
      "The list of instructions to build the dockerfile. Change them at your own risk.")
  val extraDockerInstructions = taskKey[Seq[sbtdocker.Instruction]]("A list of instructions to add to the dockerfile.")
  val verifyBlueprint = taskKey[Unit]("Verify Blueprint.")
  val printAppGraph = taskKey[Unit]("Print graph of all streamlets and how they are connected.")
  val saveAppGraph = taskKey[File]("Save graph of all streamlets and how they are connected as a text file.")
  val build = taskKey[Unit]("Build the image.")
  val buildAndPublish = taskKey[Unit]("[Deprecated! Use buildApp] Build and publish the image.")
  val runLocal = taskKey[Unit]("Run the Cloudflow application in a local Sandbox.")
  val buildApp = taskKey[Unit]("Build the Cloudflow Application CR.")

  private[sbt] val buildAndPublishImage =
    taskKey[(ImageRef, Map[String, StreamletDescriptor])]("Build and publish a project image.")
  private[sbt] val allBuildAndPublish =
    taskKey[Map[ImageRef, Map[String, StreamletDescriptor]]]("Build and push all the images.")
  private[sbt] val cloudflowWorkDir = taskKey[File]("The directory under /target used for internal bookkeeping.")
  private[sbt] val cloudflowStageAppJars = taskKey[Unit]("Stages the jars for the application.")
  private[sbt] val cloudflowStageScript = taskKey[Unit]("Stages the launch script for the application.")
  private[sbt] val allProjectsWithStreamletScannerPlugin =
    taskKey[Seq[ProjectReference]]("All projects that use the streamlet scanner plugin.")

  private[sbt] val allProjectsWithCloudflowBasePlugin =
    taskKey[Seq[ProjectReference]]("All projects that use the CloudflowBasePlugin.")

  private[sbt] val allCloudflowStreamletDescriptors =
    taskKey[ExtractResult]("Streamlets found in sub projects by scanning the application classpath.")

  private[sbt] val cloudflowStreamletDescriptors =
    taskKey[ExtractResult]("Streamlets found by scanning the application classpath.")
  private[sbt] val cloudflowStreamletDescriptorsByProject =
    taskKey[Map[String, Map[String, Config]]](
      "Streamlets found by scanning the application classpath, organized by project id.")
  private[sbt] val cloudflowApplicationClasspath = taskKey[Array[URL]]("classpath of the user project.")
  private[sbt] val cloudflowApplicationClasspathByProject =
    taskKey[(String, Array[URL])]("classpath of the user project per project.")

  private[sbt] val blueprintFile = taskKey[File]("Should be set to the blueprint in the src/main/blueprint directory.")
  private[sbt] val verificationResult = taskKey[Either[BlueprintVerificationFailed, BlueprintVerified]](
    "Verify the blueprint against the streamlets found by scanning the application classpath.")
  private[sbt] val verifiedBlueprintFile = taskKey[Option[File]]("Verified blueprint file.")

  private[sbt] val applicationDescriptor = taskKey[Option[ApplicationDescriptor]](
    "The deployment descriptor for the current application. Available if the project has a valid blueprint.")

  private[sbt] val streamletDescriptorsInProject =
    taskKey[Try[Map[String, StreamletDescriptor]]]("The class name to streamlet descriptor mapping.")
  private[sbt] val imageNamesByProject = taskKey[Map[String, DockerImageName]]("The list of all image names.")
  private[sbt] val streamletDescriptorsByProject =
    taskKey[(String, ExtractResult)]("streamlet descriptors per project.")
  val allStreamletDescriptorsByProject =
    taskKey[Map[String, ExtractResult]](
      "Streamlets found by scanning the application classpath, organized by project id.")
  private[sbt] val allApplicationClasspathByProject =
    taskKey[Map[String, Array[URL]]]("classpath of the user projects per project")

  private[sbt] val cloudflowApplicationCR = taskKey[Unit]("Generates the Cloudflow Application CR.")
}

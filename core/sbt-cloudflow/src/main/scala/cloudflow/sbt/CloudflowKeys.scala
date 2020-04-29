/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
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

import sbt._
import com.typesafe.config._

import cloudflow.blueprint.deployment.ApplicationDescriptor
import cloudflow.blueprint.StreamletDescriptor

case class DockerImageName(name: String, tag: String) {
  def asTaggedName: String = s"$name:$tag"
}

trait CloudflowSettingKeys {

  object SchemaCodeGenerator {
    sealed trait Language
    case object Java  extends Language
    case object Scala extends Language
  }

  object SchemaFormat {
    sealed trait Format
    case object Avro extends Format
  }

  val cloudflowDockerParentImage = settingKey[String]("The parent Docker image to use for Cloudflow images.")
  val blueprint                  = settingKey[Option[String]]("The path to the blueprint file to use in this Cloudflow application.")
  val schemaFormats              = settingKey[Seq[SchemaFormat.Format]]("A list of schema formats to generate source code for.")
  val schemaCodeGenerator        = settingKey[SchemaCodeGenerator.Language]("The language to generate data model schemas into.")
  val schemaPaths                = settingKey[Map[SchemaFormat.Format, String]]("A Map of paths to your data model schemas.")
  val runLocalConfigFile         = settingKey[Option[String]]("the HOCON configuration file to use with the local runner Sandbox.")
}

trait CloudflowTaskKeys {

  val cloudflowDockerImageName  = taskKey[Option[DockerImageName]]("The name of the Docker image to publish.")
  val cloudflowDockerRegistry   = taskKey[Option[String]]("The hostname and (optional) port of the Docker registry to use.")
  val cloudflowDockerRepository = taskKey[Option[String]]("The image repository name on the Docker registry.")
  val extraDockerInstructions   = taskKey[Seq[sbtdocker.Instruction]]("A list of instructions to add to the dockerfile.")

  val verifyBlueprint = taskKey[Unit]("Verify Blueprint")
  val build           = taskKey[Unit]("Build the image and app.")
  val buildAndPublish = taskKey[Unit]("Publish the image and app.")
  val runLocal        = taskKey[Unit]("Run the Cloudflow application in a local Sandbox")
  val buildApp        = taskKey[Unit]("Generate Cloudflow Application CR")

  private[sbt] val cloudflowWorkDir      = taskKey[File]("The directory under /target used for internal bookkeeping")
  private[sbt] val cloudflowStageAppJars = taskKey[Unit]("Stages the jars for the application")
  private[sbt] val cloudflowStageScript  = taskKey[Unit]("Stages the launch script for the application")

  private[sbt] val cloudflowStreamletDescriptors = taskKey[Map[String, Config]]("Streamlets found by scanning the application classpath.")
  private[sbt] val cloudflowApplicationClasspath = taskKey[Array[URL]]("classpath of the user project")

  private[sbt] val blueprintFile = taskKey[File]("Should be set to the blueprint in the src/main/blueprint directory")
  private[sbt] val verificationResult = taskKey[Either[BlueprintVerificationFailed, BlueprintVerified]](
    "Verify the blueprint against the streamlets found by scanning the application classpath."
  )
  private[sbt] val verifiedBlueprintFile = taskKey[Option[File]]("Verified blueprint file.")

  private[sbt] val applicationDescriptor = taskKey[Option[ApplicationDescriptor]](
    "The deployment descriptor for the current application. Available if the project has a valid blueprint."
  )

  private[sbt] val cloudflowBuildNumber =
    taskKey[BuildNumber]("The current Cloudflow build number (i.e. ${numberOfGitCommits}-${gitHeadCommit}).")

  private[sbt] val streamletDescriptorsInProject =
    taskKey[Map[String, StreamletDescriptor]]("The class name to streamlet descriptor mapping")
  private[sbt] val imageNamesByProject          = taskKey[Map[String, DockerImageName]]("The list of all image names")
  private[sbt] val streamletClassNamesByProject = taskKey[Map[String, Iterable[String]]]("The list of all streamlet class names by project")
  private[sbt] val cloudflowApplicationCR       = taskKey[Unit]("Generates Cloudflow Application CR")
}

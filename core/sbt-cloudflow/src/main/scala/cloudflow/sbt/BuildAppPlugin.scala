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

import java.io._

import spray.json._
import sbt._
import sbt.Keys._

import scala.util.control.NoStackTrace
import cloudflow.sbt.CloudflowKeys._
import cloudflow.blueprint.StreamletDescriptor
import cloudflow.blueprint.deployment.{ ApplicationDescriptor, CloudflowCR, Metadata }
import cloudflow.blueprint.deployment.CloudflowCRFormat.cloudflowCRFormat

/**
 * Plugin that generates the CR file for the application
 */
object BuildAppPlugin extends AutoPlugin {

  override def requires =
    StreamletDescriptorsPlugin && BlueprintVerificationPlugin

  def projectWithCloudflowBasePlugin =
    Def.task {
      val pluginName = CloudflowBasePlugin.getClass.getName.dropRight(1)
      if (thisProject.value.autoPlugins.exists(_.label == pluginName)) {
        Some(thisProjectRef.value)
      } else None
    }

  override def projectSettings = Seq(
    allProjectsWithCloudflowBasePlugin := Def.taskDyn {
          Def.task {
            projectWithCloudflowBasePlugin.all(ScopeFilter(inAnyProject)).value.flatten
          }
        }.value,
    cloudflowApplicationCR := buildApp.dependsOn(verifyBlueprint).value,
    allBuildAndPublish := (Def
          .taskDyn {
            val filter = ScopeFilter(inProjects(allProjectsWithCloudflowBasePlugin.value: _*))
            Def.task {
              val allValues = buildAndPublishImage.all(filter).value
              allValues
            }
          })
          .value
          .toMap
  )

  /**
   * Generate the Application CR from application descriptor generated by verification of
   * blueprint and the streamlet descriptors generated by the build process. We need the
   * latter since we need the proper image name associated with every streamlet descriptor
   * and streamlet deployment.
   */
  def buildApp: Def.Initialize[Task[Unit]] = Def.task {
    // these streamlet descriptors have been generated from the `build` task
    // if they have not been generated we throw an exception and ask the user
    // to run the build
    val log = streams.value.log
    // val _   = cloudflowDockerRegistry.value.getOrElse(throw DockerRegistryNotSet)

    val imageToStreamletDescriptorsMaps: Map[ImageNameAndDigest, Map[String, StreamletDescriptor]] = allBuildAndPublish.value
    val streamletClassNamesToImageNameAndId: Map[String, ImageNameAndDigest] = imageToStreamletDescriptorsMaps
      .map {
        case (imageNameAndDigest, sMap) => sMap.keys.map { _ -> imageNameAndDigest }.toSeq
      }
      .flatten
      .toMap

    if (imageToStreamletDescriptorsMaps.isEmpty) {
      throw new PreconditionViolationError("Could not find any streamlet descriptors.")
    }

    // this has been generated by `verifyBlueprint`
    val appDescriptor = applicationDescriptor.value.get

    import ImageNameExtensions._
    // need to get the proper image name in `StreamletDeployment` s too
    val newDeployments = appDescriptor.deployments.map { deployment =>
      val ImageNameAndDigest(imageName, imageDigest) = streamletClassNamesToImageNameAndId.get(deployment.className).get
      deployment.copy(image = imageName.referenceWithDigest(imageDigest))
    }

    // the new shiny `ApplicationDescriptor`
    val newApplicationDescriptor = appDescriptor.copy(deployments = newDeployments)

    // create the CR
    val cr = makeCR(newApplicationDescriptor)

    // generate the CR file in the current location
    new File("target").mkdir()
    val file = new File(s"target/${appDescriptor.appId}.json")
    IO.write(file, cr.toJson.compactPrint)
    log.success(s"Cloudflow application CR generated in ${file.getAbsolutePath}")
    log.success(s"Use the following command to deploy the Cloudflow application:")
    log.success(s"kubectl cloudflow deploy ${file.getAbsolutePath}")
  }

  def makeCR(appDescriptor: ApplicationDescriptor): CloudflowCR =
    CloudflowCR(
      apiVersion = "cloudflow.lightbend.com/v1alpha1",
      kind = "CloudflowApplication",
      metadata = Metadata(
        // @todo : need to change this version
        annotations = Map("com.lightbend.cloudflow/created-by-cli-version" -> "SNAPSHOT (local build)"),
        labels = Map(
          "app.kubernetes.io/managed-by"   -> "cloudflow",
          "app.kubernetes.io/part-of"      -> appDescriptor.appId,
          "com.lightbend.cloudflow/app-id" -> appDescriptor.appId
        ),
        name = appDescriptor.appId
      ),
      appDescriptor
    )

}

class PreconditionViolationError(msg: String) extends Exception(s"\n$msg") with NoStackTrace with sbt.FeedbackProvidedException

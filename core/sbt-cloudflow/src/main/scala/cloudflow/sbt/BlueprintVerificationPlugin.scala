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

import scala.collection.JavaConverters._
import scala.util.control.NoStackTrace
import com.typesafe.config._
import sbt._
import sbt.Keys._
import spray.json._
import JsonUtils._
import cloudflow.sbt.CloudflowKeys.{ agentPaths, blueprintFile, _ }
import cloudflow.blueprint._
import cloudflow.blueprint.deployment._
import cloudflow.blueprint.StreamletDescriptorFormat._

object BlueprintVerificationPlugin extends AutoPlugin {
  override def requires = CommonSettingsAndTasksPlugin && StreamletScannerPlugin

  override def projectSettings = Seq(
    blueprintFile := Def.taskDyn {
          Def.task {
            val defaultBlueprint = blueprintConf(baseDirectory.value)
            blueprint.value
              .map { bpFilename ⇒
                baseDirectory.value / "src" / "main" / "blueprint" / bpFilename
              }
              .getOrElse(defaultBlueprint)
          }
        }.value,
    verifyBlueprint := Def.taskDyn {
          Def.task {
            val log = streams.value.log
            feedbackResults(verificationResult.value, log)
          }
        }.value,
    verificationResult := Def.taskDyn {
          val detectedStreamlets = cloudflowStreamletDescriptors.value
          val dockerImageName    = cloudflowDockerImageName.value
          val bpFile             = blueprintFile.value
          verifiedBlueprints(bpFile, detectedStreamlets, dockerImageName)
        }.value,
    verifiedBlueprintFile := Def.taskDyn {
          val res = verificationResult.value
          writeVerifiedBlueprintFile(res)
        }.value,
    mappings in (Compile, packageBin) ++= {
      val _ = verifyBlueprint.value // dependency
      verifiedBlueprintFile.value.map { bpFile ⇒
        bpFile -> bpFile.getName
      }
    },
    applicationDescriptor := {
      val appId           = (ThisProject / name).value
      val appVersion      = cloudflowBuildNumber.value.buildNumber
      val agentPathsMap   = agentPaths.value
      val dockerImageName = cloudflowDockerImageName.value

      for {
        BlueprintVerified(bp, _) ← verificationResult.value.toOption
        verifiedBlueprint        ← bp.verified.toOption
      } yield ApplicationDescriptor(appId, appVersion, dockerImageName.get.name, verifiedBlueprint, agentPathsMap)
    },
    fork in Compile := true
  )

  private def blueprintConf(base: File): File = base / "src" / "main" / "blueprint" / "blueprint.conf"

  private def verifiedBlueprints(
      bpFile: sbt.File,
      detectedStreamlets: Map[String, Config],
      dockerImageName: Option[DockerImageName]
  ): Def.Initialize[Task[Either[BlueprintVerificationFailed, BlueprintVerified]]] = Def.task {

    val detectedStreamletDescriptors = detectedStreamlets.map {
      case (_, configDescriptor) ⇒
        // some ducktape for now
        val jsonString = configDescriptor.root().render(ConfigRenderOptions.concise())
        dockerImageName
          .map(din ⇒ jsonString.parseJson.addField("image", din.asTaggedName))
          .getOrElse(jsonString.parseJson.addField("image", "placeholder"))
          .convertTo[cloudflow.blueprint.StreamletDescriptor]
    }

    val streamletDescriptors = detectedStreamletDescriptors

    //TODO cleanup: separate into a 'BlueprintConfigFormat.parse'
    bpFile.allPaths
      .get()
      .headOption
      .map { bpFile ⇒
        val config = ConfigFactory.parseString(IO.read(bpFile))
        val streamletRefs = config
          .getConfig("blueprint.streamlets")
          .entrySet
          .asScala
          .map { e ⇒
            StreamletRef(
              name = e.getKey,
              className = config.getString(s"blueprint.streamlets.${e.getKey}")
            )
          }
          .toVector

        val streamletConnections = config
          .getConfig("blueprint.connections")
          .entrySet
          .asScala
          .flatMap { e ⇒
            val inlets = config.getStringList(s"blueprint.connections.${e.getKey}").asScala
            inlets.map { inlet ⇒
              StreamletConnection(
                from = e.getKey,
                to = inlet
              )
            }
          }
          .toVector

        val blueprint =
          Blueprint(streamletRefs, streamletConnections, streamletDescriptors.toVector).verify

        if (blueprint.problems.isEmpty) {
          Right(BlueprintVerified(blueprint, bpFile))
        } else {
          Left(BlueprintRuleViolations(blueprint, bpFile))
        }
      }
      .getOrElse {
        Left(BlueprintDoesNotExist(bpFile))
      }
  }

  private def writeVerifiedBlueprintFile(
      results: Either[BlueprintVerificationFailed, BlueprintVerified]
  ): Def.Initialize[Task[Option[File]]] = Def.task {
    results.toOption.map { blueprintVerified ⇒
      val file = (target in Compile).value / "blueprint" / blueprintVerified.file.getName
      IO.copyFile(blueprintVerified.file, file)
      file
    }
  }

  private def feedbackResults(results: Either[BlueprintVerificationFailed, BlueprintVerified], log: sbt.internal.util.ManagedLogger): Unit =
    results match {
      case Left(BlueprintDoesNotExist(file)) ⇒
        throw new BlueprintVerificationError(s"The blueprint file does not exist:\n${file.toString}")
      case Left(BlueprintRuleViolations(blueprint, file)) ⇒
        val problemsMsg = blueprint.problems
          .map { p ⇒
            BlueprintProblem.toMessage(p)
          }
          .mkString("\n")
        throw new BlueprintVerificationError(s"${file.toString}:\n$problemsMsg")
      case Right(BlueprintVerified(_, file)) ⇒
        log.success(s"${file.toString} verified.")
    }
}

sealed trait BlueprintVerificationFailed
case class BlueprintRuleViolations(blueprint: Blueprint, file: File) extends BlueprintVerificationFailed
case class BlueprintDoesNotExist(file: File)                         extends BlueprintVerificationFailed
case class BlueprintVerified(blueprint: Blueprint, file: File)

class BlueprintVerificationError(msg: String) extends Exception(s"\n$msg") with NoStackTrace with sbt.FeedbackProvidedException

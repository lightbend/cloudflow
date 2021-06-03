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

import scala.util.{ Failure, Success, Try }
import scala.util.control.NoStackTrace
import com.typesafe.config._
import sbt._
import sbt.Keys._
import spray.json._
import JsonUtils._
import cloudflow.sbt.CloudflowKeys.{ blueprintFile, _ }
import cloudflow.blueprint._
import cloudflow.blueprint.deployment._
import cloudflow.blueprint.StreamletDescriptorFormat._
import cloudflow.extractor.ExtractResult

object BlueprintVerificationPlugin extends AutoPlugin {

  override def requires = CommonSettingsAndTasksPlugin && StreamletScannerPlugin

  def projectWithStreamletScannerPlugin =
    Def.task {
      val pluginName = StreamletScannerPlugin.getClass.getName.dropRight(1)
      if (thisProject.value.autoPlugins.exists(_.label == pluginName)) {
        Some(thisProjectRef.value)
      } else None
    }

  override def projectSettings =
    Seq(
      blueprintFile := Def.taskDyn {
          Def.task {
            val defaultBlueprint = blueprintConf(baseDirectory.value)
            blueprint.value
              .map { bpFilename =>
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
      allProjectsWithStreamletScannerPlugin := Def.taskDyn {
          Def.task {
            projectWithStreamletScannerPlugin.all(ScopeFilter(inAnyProject)).value.flatten
          }
        }.value,
      allCloudflowStreamletDescriptors := composeExtractResults(Def.taskDyn {
          val filter = ScopeFilter(inProjects(allProjectsWithStreamletScannerPlugin.value: _*))
          Def.task {
            val allValues = cloudflowStreamletDescriptors.all(filter).value
            allValues
          }
        }.value),
      verificationResult := Def.taskDyn {
          val bpFile = blueprintFile.value
          val detectedStreamlets = allCloudflowStreamletDescriptors.value
          verifiedBlueprints(bpFile, detectedStreamlets)
        }.value,
      verifiedBlueprintFile := Def.taskDyn {
          val res = verificationResult.value
          writeVerifiedBlueprintFile(res)
        }.value,
      mappings in (Compile, packageBin) ++= {
        val _ = verifyBlueprint.value // dependency
        verifiedBlueprintFile.value.map { bpFile =>
          bpFile -> bpFile.getName
        }
      },
      applicationDescriptor := {
        val appId = (ThisProject / name).value
        val appVersion = (ThisProject / version).value
        val agentPathsMap = Map("prometheus" -> "/prometheus/jmx_prometheus_javaagent.jar")
        val dockerImageName = cloudflowDockerImageName.value
        val libraryVersion = (ThisProject / cloudflowVersion).value

        for {
          BlueprintVerified(bp, _) <- verificationResult.value.toOption
          verifiedBlueprint <- bp.verified.toOption
        } yield ApplicationDescriptor(
          appId,
          appVersion,
          dockerImageName.get.name,
          verifiedBlueprint,
          agentPathsMap,
          libraryVersion)
      },
      fork in Compile := true)

  private def composeExtractResults(results: Seq[ExtractResult]): ExtractResult =
    results.foldLeft(ExtractResult()) { (acc, el) =>
      acc.copy(descriptors = acc.descriptors ++ el.descriptors, problems = acc.problems ++ el.problems)
    }

  private def blueprintConf(base: File): File = base / "src" / "main" / "blueprint" / "blueprint.conf"

  private def verifiedBlueprints(
      bpFile: sbt.File,
      extractResult: ExtractResult): Def.Initialize[Task[Either[BlueprintVerificationFailed, BlueprintVerified]]] =
    Def.task {
      val streamletDescriptors = extractResult.descriptors.map {
        case (_, configDescriptor) =>
          configDescriptor
            .root()
            .render(ConfigRenderOptions.concise())
            .parseJson
            .addField("image", "placeholder")
            .convertTo[cloudflow.blueprint.StreamletDescriptor]
      }

      bpFile.allPaths
        .get()
        .headOption
        .map { bpFile =>
          val blueprint = Blueprint
            .parseString(IO.read(bpFile), streamletDescriptors.toVector)

          val blueprintWithExtractProblems =
            blueprint.copy(globalProblems = blueprint.globalProblems ++ extractResult.problems.map {
                case extractProblem: cloudflow.extractor.ExtractProblem =>
                  cloudflow.blueprint.ExtractProblem(extractProblem.message)
              })
          if (blueprintWithExtractProblems.problems.isEmpty) {
            Right(BlueprintVerified(blueprintWithExtractProblems, bpFile))
          } else {
            Left(BlueprintRuleViolations(blueprintWithExtractProblems, bpFile))
          }
        }
        .getOrElse {
          Left(BlueprintDoesNotExist(bpFile))
        }

    }

  private def writeVerifiedBlueprintFile(
      results: Either[BlueprintVerificationFailed, BlueprintVerified]): Def.Initialize[Task[Option[File]]] = Def.task {
    results.toOption.map { blueprintVerified =>
      val file = (target in Compile).value / "blueprint" / blueprintVerified.file.getName
      IO.copyFile(blueprintVerified.file, file)
      file
    }
  }

  private def feedbackResults(
      results: Either[BlueprintVerificationFailed, BlueprintVerified],
      log: sbt.internal.util.ManagedLogger): Unit =
    results match {
      case Left(BlueprintDoesNotExist(file)) =>
        throw new BlueprintVerificationError(s"The blueprint file does not exist:\n${file.toString}")
      case Left(BlueprintRuleViolations(blueprint, file)) =>
        val problemsMsg = blueprint.problems
          .map { p =>
            BlueprintProblem.toMessage(p)
          }
          .mkString("\n")
        throw new BlueprintVerificationError(s"${file.toString}:\n$problemsMsg")
      case Right(BlueprintVerified(_, file)) =>
        log.success(s"${file.toString} verified.")
    }
}

sealed trait BlueprintVerificationFailed
case class BlueprintRuleViolations(blueprint: Blueprint, file: File) extends BlueprintVerificationFailed
case class BlueprintDoesNotExist(file: File) extends BlueprintVerificationFailed
case class BlueprintVerified(blueprint: Blueprint, file: File)

class BlueprintVerificationError(msg: String)
    extends Exception(s"\n$msg")
    with NoStackTrace
    with sbt.FeedbackProvidedException

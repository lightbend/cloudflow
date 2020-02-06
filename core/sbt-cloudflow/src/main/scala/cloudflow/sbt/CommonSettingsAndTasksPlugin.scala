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

import java.io.File

import com.lightbend.sbt.javaagent.JavaAgent.JavaAgentKeys._
import sbt.Keys._
import sbt._
import sbtavro.SbtAvro.autoImport._
import sbtavrohugger.SbtAvrohugger.autoImport._

/**
 * SBT Plugin that centralizes the use of common keys for Cloudflow projects.
 */
object CommonSettingsAndTasksPlugin extends AutoPlugin {
  /** This plugin depends on these other plugins: */
  override def requires: Plugins =
    BuildNumberPlugin &&
      sbtavrohugger.SbtAvrohugger &&
      sbtavro.SbtAvro

  /** Make public keys available. */
  object autoImport extends CloudflowKeys

  import autoImport._

  // common definitions
  final val CloudflowLocalConfigFile = ".lightbend/cloudflow/pipectl.json"
  final val CloudflowDockerBaseImage = "lightbend/cloudflow-base:1.3.0-M1-spark-2.4.4-flink-1.9.2-scala-2.12"
  // used for internal release
  final val CloudflowBintrayReleasesRepoUrl = "https://lightbend.bintray.com/cloudflow"

  /** Set default values for keys. */
  override def projectSettings = Seq(
    // TODO: currently required for our custom build of Akka. Remove when our features have been merged.
    resolvers += "Akka Snapshots" at "https://repo.akka.io/snapshots/",
    // Cloudflow is released with Ivy patterns - bintray is used for internal release
    resolvers += Resolver.url("cloudflow", url(CloudflowBintrayReleasesRepoUrl))(Resolver.ivyStylePatterns),

    cloudflowDockerParentImage := CloudflowDockerBaseImage,

    cloudflowDockerImageName := Def.task {
      Some(DockerImageName((ThisProject / name).value.toLowerCase, (ThisProject / cloudflowBuildNumber).value.buildNumber))
    }.value,

    agentPaths := Def.taskDyn {
      Def.task {
        resolvedJavaAgents.value.filter(_.agent.scope.dist).map { resolved ⇒
          resolved.agent.name -> (
            ImagePlugin.AppTargetDir + File.separator +
            Project.normalizeModuleID(resolved.agent.name) +
            File.separator + resolved.artifact.name
          )
        }.toMap
      }
    }.value,

    publishArtifact in (Compile, packageDoc) := false,
    publishArtifact in (Compile, packageSrc) := false,

    libraryDependencies += "com.twitter" %% "bijection-avro" % "0.9.6",

    schemaFormats := Seq(SchemaFormat.Avro),
    schemaCodeGenerator := SchemaCodeGenerator.Scala,
    schemaPaths := Map(SchemaFormat.Avro -> "src/main/avro"),

    AvroConfig / stringType := "String", // sbt-avro `String` type name
    AvroConfig / sourceDirectory := baseDirectory.value / schemaPaths.value(SchemaFormat.Avro), // sbt-avro source directory
    Compile / avroSourceDirectories += baseDirectory.value / schemaPaths.value(SchemaFormat.Avro), // sbt-avrohugger source directory

    Compile / sourceGenerators := {
      val generators = (sourceGenerators in Compile).value
      val schemaLang = schemaCodeGenerator.value
      val clean = filterGeneratorTask(generators, generate, AvroConfig)

      schemaLang match {
        case SchemaCodeGenerator.Java  ⇒ clean :+ (generate in AvroConfig).taskValue
        case SchemaCodeGenerator.Scala ⇒ clean :+ (avroScalaGenerateSpecific in Compile).taskValue
      }
    }
  )

  // ideally we could use `-=` to simply remove the Java Avro generator added by sbt-avro, but that's not possible
  // because sourceGenerator's are a list of SBT Task's that have no equality semantics.
  def filterGeneratorTask(generators: Seq[Task[Seq[File]]], taskKey: TaskKey[_], config: Configuration) = {
    def toScopedKey(entry: AttributeEntry[_]) =
      for (k ← Option(entry.value.asInstanceOf[ScopedKey[_]]))
        yield (k.key, k.scope.config)

    generators.filterNot { task ⇒
      task.info.attributes.entries.toList.map(toScopedKey).exists {
        case Some((key, Select(ConfigKey(configName)))) ⇒ taskKey.key == key && configName == config.name
        case _                                          ⇒ false
      }
    }
  }
}

trait CloudflowKeys extends CloudflowSettingKeys with CloudflowTaskKeys
object CloudflowKeys extends CloudflowKeys

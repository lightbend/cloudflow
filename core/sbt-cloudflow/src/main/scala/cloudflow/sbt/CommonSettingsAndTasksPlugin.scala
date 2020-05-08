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

import sbt.Keys._
import sbt._

/**
 * SBT Plugin that centralizes the use of common keys for Cloudflow projects.
 */
object CommonSettingsAndTasksPlugin extends AutoPlugin {
  import sbtavro.SbtAvro.autoImport._
  import sbtavrohugger.SbtAvrohugger.autoImport._
  import akka.grpc.sbt._
  import akka.grpc.sbt.AkkaGrpcPlugin.autoImport._
  import sbtprotoc.ProtocPlugin.autoImport._

  /** This plugin depends on these other plugins: */
  override def requires: Plugins =
    BuildNumberPlugin &&
      sbtavrohugger.SbtAvrohugger &&
      sbtavro.SbtAvro && AkkaGrpcPlugin

  /** Make public keys available. */
  object autoImport extends CloudflowKeys

  import autoImport._

  // common definitions
  final val CloudflowLocalConfigFile = ".lightbend/cloudflow/pipectl.json"
  // used for internal release
  final val CloudflowBintrayReleasesRepoUrl = "https://lightbend.bintray.com/cloudflow"

  /** Set default values for keys. */
  override def projectSettings =
    Seq(
      // Cloudflow is released with Ivy patterns - bintray is used for internal release
      resolvers += Resolver.url("cloudflow", url(CloudflowBintrayReleasesRepoUrl))(Resolver.ivyStylePatterns),
      cloudflowDockerImageName := Def.task {
            Some(DockerImageName((ThisProject / name).value.toLowerCase, (ThisProject / cloudflowBuildNumber).value.buildNumber))
          }.value,
      cloudflowWorkDir := (ThisBuild / baseDirectory).value / "target" / ".cloudflow",
      imageNamesByProject := Def.taskDyn {
            val buildNumber = cloudflowBuildNumber.value.buildNumber
            Def.task {
              buildStructure.value.allProjectRefs
                .map(_.project)
                .foldLeft(Map.empty[String, DockerImageName]) { (a, e) =>
                  a + (e.toLowerCase -> DockerImageName(e.toLowerCase, buildNumber))
                }
            }
          }.value,
      publishArtifact in (Compile, packageDoc) := false,
      publishArtifact in (Compile, packageSrc) := false,
      libraryDependencies += "com.twitter"     %% "bijection-avro" % "0.9.7",
      libraryDependencies += "org.apache.avro" % "avro"            % "1.8.2",
      schemaCodeGenerator := SchemaCodeGenerator.Scala,
      schemaPaths := Map(
            SchemaFormat.Avro  -> "src/main/avro",
            SchemaFormat.Proto -> "src/main/protobuf"
          ),
      akkaGrpcGeneratedLanguages := {
        val schemaLang = schemaCodeGenerator.value
        schemaLang match {
          case SchemaCodeGenerator.Java  ⇒ Seq(AkkaGrpc.Java)
          case SchemaCodeGenerator.Scala ⇒ Seq(AkkaGrpc.Scala)
        }
      },
      AvroConfig / generate := Def.taskDyn {
            val default    = (generate in AvroConfig).taskValue
            val schemaLang = schemaCodeGenerator.value
            schemaLang match {
              case SchemaCodeGenerator.Java  ⇒ Def.task(default.value)
              case SchemaCodeGenerator.Scala ⇒ Def.task { Seq.empty[File] }
            }
          }.value,
      AvroConfig / javaSource := (crossTarget in Compile).value / "java_avro",                    // sbt-avro generated java source
      AvroConfig / stringType := "String",                                                        // sbt-avro `String` type name
      AvroConfig / sourceDirectory := baseDirectory.value / schemaPaths.value(SchemaFormat.Avro), // sbt-avro source directory
      Compile / avroSpecificSourceDirectories += baseDirectory.value / schemaPaths
                .value(SchemaFormat.Avro),                                                // sbt-avrohugger source directory
      Compile / avroSpecificScalaSource := (crossTarget in Compile).value / "scala_avro", // sbt-avrohugger generated scala source
      Compile / sourceGenerators := {
        val schemaLang = schemaCodeGenerator.value
        schemaLang match {
          case SchemaCodeGenerator.Java  ⇒ Seq((generate in AvroConfig).taskValue)
          case SchemaCodeGenerator.Scala ⇒ Seq((avroScalaGenerateSpecific in Compile).taskValue)
        }
      }
    ) ++ inConfig(Compile)(
          Seq(
            PB.protoSources += sourceDirectory.value / schemaPaths.value(SchemaFormat.Proto)
          )
        )
}

trait CloudflowKeys  extends CloudflowSettingKeys with CloudflowTaskKeys
object CloudflowKeys extends CloudflowKeys

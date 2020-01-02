//tag::docs-projectSetup-example[]
import sbt._
import sbt.Keys._

import scalariform.formatter.preferences._

lazy val tensorflowAkka =  (project in file("."))
    .enablePlugins(CloudflowAkkaStreamsApplicationPlugin)
    .settings(
//end::docs-projectSetup-example[]
      libraryDependencies ++= Seq(
        "ch.qos.logback"         %  "logback-classic"           % "1.2.3",
        "com.typesafe.akka"      %% "akka-http-testkit"         % "10.1.10" % "test",
        "org.tensorflow"         % "tensorflow"                 % "1.15.0",
        "org.tensorflow"         % "proto"                      % "1.15.0",
        "org.scalatest"          %% "scalatest"                 % "3.0.8"  % "test"
//tag::docs-projectName-example[]
      ),
      name := "tensorflow-akka",
//end::docs-projectName-example[]
      organization := "com.lightbend.cloudflow",
      headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2020", "Lightbend Inc. <https://www.lightbend.com>")),

      scalaVersion := "2.12.10",
      crossScalaVersions := Vector(scalaVersion.value),
      scalacOptions ++= Seq(
        "-encoding", "UTF-8",
        "-target:jvm-1.8",
        "-Xlog-reflective-calls",
        "-Xlint",
        "-Ywarn-unused",
        "-Ywarn-unused-import",
        "-deprecation",
        "-feature",
        "-language:_",
        "-unchecked"
      ),

      runLocalConfigFile := Some("src/main/resources/local.conf"),

      scalacOptions in (Compile, console) --= Seq("-Ywarn-unused", "-Ywarn-unused-import"),
      scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value,

      scalariformPreferences := scalariformPreferences.value
        .setPreference(AlignParameters, false)
        .setPreference(AlignSingleLineCaseStatements, true)
        .setPreference(AlignSingleLineCaseStatements.MaxArrowIndent, 90)
        .setPreference(DoubleIndentConstructorArguments, true)
        .setPreference(DoubleIndentMethodDeclaration, true)
        .setPreference(RewriteArrowSymbols, true)
        .setPreference(DanglingCloseParenthesis, Preserve)
        .setPreference(NewlineAtEndOfFile, true)
        .setPreference(AllowParamGroupsOnNewlines, true)
    )

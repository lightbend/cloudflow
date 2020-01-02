import sbt._
import sbt.Keys._

import scalariform.formatter.preferences._

lazy val sensorDataJava =  (project in file("."))
    .enablePlugins(CloudflowAkkaStreamsApplicationPlugin)
    .settings(
      libraryDependencies ++= Seq(
        "com.lightbend.akka"     %% "akka-stream-alpakka-file"  % "1.1.2",
        "com.typesafe.akka"      %% "akka-http-spray-json"      % "10.1.10",
        "ch.qos.logback"         %  "logback-classic"           % "1.2.3",
        "org.scalatest"          %% "scalatest"                 % "3.0.8"    % "test",
        "junit"                  % "junit"                      % "4.12"     % "test"
      ),

      name := "sensor-data-java",
      organization := "com.lightbend.cloudflow",
      headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2020", "Lightbend Inc. <https://www.lightbend.com>")),

      schemaCodeGenerator := SchemaCodeGenerator.Java,

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
      javacOptions ++= Seq("-Xlint:deprecation"),

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

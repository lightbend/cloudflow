//tag::docs-projectSetup-example[]
import sbt._
import sbt.Keys._

lazy val tensorflowAkka =  (project in file("."))
    .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin, ScalafmtPlugin)
    .settings(
//end::docs-projectSetup-example[]
      scalafmtOnCompile := true,
      libraryDependencies ++= Seq(
        Cloudflow.library.CloudflowAvro,
        "ch.qos.logback"         %  "logback-classic"           % "1.2.11",
        "com.typesafe.akka"      %% "akka-http-testkit"         % "10.1.12" % "test",
        "org.tensorflow"         %  "tensorflow"                % "1.15.0",
        "org.tensorflow"         %  "proto"                     % "1.15.0",
        "org.scalatest"          %% "scalatest"                 % "3.0.8"  % "test"
//tag::docs-projectName-example[]
      ),
      name := "tensorflow-akka",
//end::docs-projectName-example[]
      organization := "com.lightbend.cloudflow",
      headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2020", "Lightbend Inc. <https://www.lightbend.com>")),

      scalaVersion := "2.12.15",
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
      Compile / sourceGenerators += (Compile / avroScalaGenerateSpecific).taskValue,
      runLocalConfigFile := Some("src/main/resources/local.conf"),
      Compile / console / scalacOptions --= Seq("-Ywarn-unused", "-Ywarn-unused-import"),
      Test / console / scalacOptions := (Compile / console / scalacOptions).value,
    )

ThisBuild / dynverSeparator := "-"

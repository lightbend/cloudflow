import sbt._
import sbt.Keys._
import Dependencies._

lazy val window_processing = (project in file("."))
  .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin, ScalafmtPlugin)
  .settings(
    scalafmtOnCompile := true,
    libraryDependencies ++= Seq(slf4jAPI, slf4jLog4J, scalaTest % Test),
    name := "window-processing",
    organization := "com.lightbend.cloudflow",
    headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2020", "Lightbend Inc. <https://www.lightbend.com>")),

    scalaVersion := "2.12.11",
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

    scalacOptions in (Compile, console) --= Seq("-Ywarn-unused", "-Ywarn-unused-import"),
    scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value,
  )

dynverSeparator in ThisBuild := "-"

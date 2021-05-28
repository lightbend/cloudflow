import sbt._
import sbt.Keys._

lazy val sparkSensors = Project(id = "spark-sensors-proto", base = file("."))
    .enablePlugins(CloudflowApplicationPlugin, CloudflowSparkPlugin, ScalafmtPlugin)
    .settings(
      scalafmtOnCompile := true,
      libraryDependencies ++= Seq(
        "ch.qos.logback" %  "logback-classic" % "1.2.3",
        "org.scalatest"  %% "scalatest"       % "3.0.8" % "test"
      ),

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

      Compile / console / scalacOptions --= Seq("-Ywarn-unused", "-Ywarn-unused-import"),
      Test / console / scalacOptions := (Compile / console / scalacOptions).value,
    )

ThisBuild / dynverSeparator := "-"

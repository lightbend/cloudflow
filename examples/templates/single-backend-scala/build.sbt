import sbt._
import sbt.Keys._

lazy val templateScala = (project in file("."))
  .enablePlugins(CloudflowAkkaPlugin, CloudflowApplicationPlugin, ScalafmtPlugin)
  .settings(
    scalafmtOnCompile := true,
    libraryDependencies ++= Seq(
          "ch.qos.logback" % "logback-classic" % "1.2.10",
          "org.scalatest"  %% "scalatest"      % "3.0.8" % "test"
        ),
    name := "template-scala",
    organization := "com.lightbend.cloudflow",
    headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2020", "Lightbend Inc. <https://www.lightbend.com>")),
    scalaVersion := "2.12.15",
    crossScalaVersions := Vector(scalaVersion.value),
    scalacOptions ++= Seq(
          "-encoding",
          "UTF-8",
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
    Compile / console / scalacOptions --= Seq("-Ywarn-unused", "-Ywarn-unused-import"),
    Test / console / scalacOptions := (Compile / console / scalacOptions).value
  )

ThisBuild / dynverSeparator := "-"

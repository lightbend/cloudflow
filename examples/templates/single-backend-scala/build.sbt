import sbt._
import sbt.Keys._

lazy val templateScala = (project in file("."))
  .enablePlugins(CloudflowAkkaStreamsApplicationPlugin, ScalafmtPlugin)
  .settings(
    scalafmtOnCompile := true,
    libraryDependencies ++= Seq(
          "ch.qos.logback" % "logback-classic" % "1.2.3",
          "org.scalatest"  %% "scalatest"      % "3.0.8" % "test"
        ),
    name := "template-scala",
    organization := "com.lightbend.cloudflow",
    headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2020", "Lightbend Inc. <https://www.lightbend.com>")),
    scalaVersion := "2.12.10",
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
    scalacOptions in (Compile, console) --= Seq("-Ywarn-unused", "-Ywarn-unused-import"),
    scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value
  )

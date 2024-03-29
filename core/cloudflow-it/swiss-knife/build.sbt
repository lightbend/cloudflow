import sbt._
import sbt.Keys._

lazy val swissKnife = (project in file("."))
    .enablePlugins(ScalafmtPlugin)
    .settings(
      scalafmtOnCompile := true,
      organization := "com.lightbend.cloudflow",
      headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2021", "Lightbend Inc. <https://www.lightbend.com>"))
    )
    .settings(commonSettings)
    .aggregate(
      app,
      datamodel,
      akka
    )
lazy val app = (project in file("./app"))
  .settings(
    name:= "swiss-knife"
  )
  .enablePlugins(CloudflowApplicationPlugin)
  .settings(commonSettings)
  .settings(
    name := "swiss-knife",
    runLocalConfigFile := Some("app/src/main/resources/local.conf"),
  )
  
lazy val datamodel = (project in file("datamodel"))
  .settings(
    commonSettings,
    Compile / sourceGenerators += (Compile / avroScalaGenerateSpecific).taskValue,
    libraryDependencies += Cloudflow.library.CloudflowAvro
  )

lazy val akka = (project in file("./akka"))
  .enablePlugins(CloudflowAkkaPlugin)
  .settings(commonSettings)
  .settings(
    name := "swiss-knife-akka",
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.2.11",
    )
  )
  .dependsOn(datamodel)

lazy val commonSettings = Seq(
  headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2020", "Lightbend Inc. <https://www.lightbend.com>")),
  scalaVersion := "2.12.15",
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
  )
)

dynverSeparator in ThisBuild := "-"

import sbt._
import sbt.Keys._

val AkkaVersion = "2.6.6"

lazy val root =
  Project(id = "root", base = file("."))
    .enablePlugins(ScalafmtPlugin)
    .settings(
      name := "root",
      scalafmtOnCompile := true,
      skip in publish := true,
    )
    .withId("root")
    .settings(commonSettings)
    .aggregate(
      connectedCarExample,
      datamodel,
      akkaConnectedCar
    )

lazy val connectedCarExample = (project in file("./akka-connected-car"))
  .enablePlugins(CloudflowApplicationPlugin)
  .settings(
    commonSettings,
    name := "connected-car-akka-cluster",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.0.7" % "test"
      )
  )

lazy val datamodel = (project in file("./datamodel"))
  .enablePlugins(CloudflowLibraryPlugin)

lazy val akkaConnectedCar= (project in file("./akka-connected-car-streamlet"))
  .enablePlugins(CloudflowAkkaPlugin)
  .settings(
    commonSettings,
    name := "akka-connected-car-streamlet",
    libraryDependencies ++= Seq(
      "ch.qos.logback" %  "logback-classic" % "1.2.3",
      "org.scalatest"  %% "scalatest"       % "3.0.8"  % "test"
    )
  )
  .dependsOn(datamodel)

lazy val commonSettings = Seq(
  organization := "com.lightbend.cloudflow",
  headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2020", "Lightbend Inc. <https://www.lightbend.com>")),
  scalaVersion := "2.12.11",
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
  scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value

)

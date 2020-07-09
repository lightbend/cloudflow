import sbt._
import sbt.Keys._

//tag::local-conf[]
lazy val sensorData =  (project in file("."))
    .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin, ScalafmtPlugin)
    .settings(
      scalaVersion := "2.12.11",
      runLocalConfigFile := Some("src/main/resources/local.conf"), //<1>
      scalafmtOnCompile := true,
      name := "sensor-data-scala",
//end::local-conf[]      

      libraryDependencies ++= Seq(
        "com.lightbend.akka"     %% "akka-stream-alpakka-file"  % "1.1.2",
        "com.typesafe.akka"      %% "akka-http-spray-json"      % "10.1.12",
        "ch.qos.logback"         %  "logback-classic"           % "1.2.3",
        "com.typesafe.akka"      %% "akka-http-testkit"         % "10.1.12" % "test",
        "org.scalatest"          %% "scalatest"                 % "3.0.8"  % "test"
      ),
      organization := "com.lightbend.cloudflow",
      headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2020", "Lightbend Inc. <https://www.lightbend.com>")),

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
      scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value

    )
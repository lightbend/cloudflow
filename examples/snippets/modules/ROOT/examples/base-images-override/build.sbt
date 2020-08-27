import sbt._
import sbt.Keys._

    //tag::docs-projectSetup-example[]
lazy val sampleApp = (project in file("."))
    .settings(
      cloudflowAkkaBaseImage := "myRepositoryUrl/myRepositoryPath:2.0.9-cloudflow-akka-2.6.6-scala-2.12",
    //end::docs-projectSetup-example[]
      name := "sample-app",
      organization := "com.lightbend.cloudflow",
      scalaVersion := "2.12.11",
      libraryDependencies ++= Seq(
        "com.lightbend.akka"     %% "akka-stream-alpakka-file"  % "1.1.2",
        "com.typesafe.akka"      %% "akka-http-spray-json"      % "10.1.12",
        "ch.qos.logback"         %  "logback-classic"           % "1.2.3",
        "com.typesafe.akka"      %% "akka-http-testkit"         % "10.1.12" % "test",
        "org.scalatest"          %% "scalatest"                 % "3.0.8"  % "test"
      )
    )

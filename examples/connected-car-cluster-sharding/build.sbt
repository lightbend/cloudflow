import sbt._
import sbt.Keys._

import scalariform.formatter.preferences._

lazy val root = connectedCarExample

val AkkaVersion         = "2.5.29"

lazy val commonSettings = Seq(
  organization := "com.lightbend",
  version := "0.0.4",
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),

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
  scalaVersion := "2.12.8",

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

lazy val connectedCarExample = (project in file("./akka-connected-car"))
  .enablePlugins(CloudflowApplicationPlugin)
  .settings(
    commonSettings,
    name := "connected-car-akka-cluster",
    libraryDependencies ++= Seq(
      "ch.qos.logback" %  "logback-classic" % "1.2.3",
      "org.scalatest"          %% "scalatest"              % "3.0.7"    % "test"
      )
  )
  .dependsOn(akkaConnectedCar)

lazy val datamodel = (project in file("./datamodel"))
  .enablePlugins(CloudflowLibraryPlugin)
  .settings(
    commonSettings,
    name := "connected-car-data-model",
    libraryDependencies ++= Seq(
      "com.twitter" %% "bijection-avro" % "0.9.6"
    ),
    (sourceGenerators in Compile) += (avroScalaGenerateSpecific in Compile).taskValue,
  )

lazy val akkaConnectedCar= (project in file("./akka-connected-car-streamlet"))
  .enablePlugins(CloudflowAkkaStreamsLibraryPlugin)
  .settings(
    commonSettings,
    name := "connected-car-akka-streams",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-cluster-sharding" % AkkaVersion,
      "net.liftweb" %% "lift-json" % "3.3.0"
    )
  )
  .dependsOn(datamodel)
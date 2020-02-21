import sbt._
import sbt.Keys._

lazy val root =
  Project(id = "root", base = file("."))
    .settings(
      name := "root",
      skip in publish := true,
    )
    .withId("root")
    .settings(commonSettings)
    .aggregate(
      callRecordPipeline,
      datamodel,
      akkaCdrIngestor,
      akkaJavaAggregationOutput,
      sparkAggregation
    )

//tag::docs-CloudflowApplicationPlugin-example[]
lazy val callRecordPipeline = appModule("call-record-pipeline")
  .enablePlugins(CloudflowApplicationPlugin)
  .settings(commonSettings)
  .settings(
    name := "call-record-aggregator"
  )
  .dependsOn(akkaCdrIngestor, akkaJavaAggregationOutput, sparkAggregation)
//end::docs-CloudflowApplicationPlugin-example[]

lazy val datamodel = appModule("datamodel")
  .enablePlugins(CloudflowLibraryPlugin)

lazy val akkaCdrIngestor= appModule("akka-cdr-ingestor")
    .enablePlugins(CloudflowAkkaStreamsLibraryPlugin)
    .settings(
      commonSettings,
      libraryDependencies ++= Seq(
        "com.typesafe.akka"         %% "akka-http-spray-json"   % "10.1.11",
        "ch.qos.logback"            %  "logback-classic"        % "1.2.3",
        "org.scalatest"             %% "scalatest"              % "3.0.8"    % "test"
      )
    )
  .dependsOn(datamodel)

lazy val akkaJavaAggregationOutput= appModule("akka-java-aggregation-output")
  .enablePlugins(CloudflowAkkaStreamsLibraryPlugin)
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "com.typesafe.akka"      %% "akka-http-spray-json"   % "10.1.11",
      "ch.qos.logback"         %  "logback-classic"        % "1.2.3",
      "org.scalatest"          %% "scalatest"              % "3.0.8"    % "test"
    )
  )
  .dependsOn(datamodel)

lazy val sparkAggregation = appModule("spark-aggregation")
    .enablePlugins(CloudflowSparkLibraryPlugin)
    .settings(
      commonSettings,
      Test / parallelExecution := false,
      Test / fork := true,
      libraryDependencies ++= Seq(
        "ch.qos.logback" %  "logback-classic" % "1.2.3",
        "org.scalatest"  %% "scalatest"       % "3.0.8"  % "test"
      )
    )
  .dependsOn(datamodel)

def appModule(moduleID: String): Project = {
  Project(id = moduleID, base = file(moduleID))
    .settings(
      name := moduleID
    )
    .withId(moduleID)
    .settings(commonSettings)
}

lazy val commonSettings = Seq(
  organization := "com.lightbend.cloudflow",
  headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2020", "Lightbend Inc. <https://www.lightbend.com>")),
  scalaVersion := "2.12.10",
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

lazy val root =
  Project(id = "root", base = file("."))
    .aggregate(
      step0,
      step1,
      step2,
      step3,
      app)

def appModule(moduleID: String): Project = {
  Project(id = moduleID, base = file(moduleID))
    .settings(
      name := moduleID
    )
    .withId(moduleID)
    .settings(commonSettings)
}

lazy val step0 = appModule("step0")
    .enablePlugins(CloudflowFlinkPlugin)
    .settings(
      Test / parallelExecution := false,
      Test / fork := true
    )

lazy val step1 = appModule("step1")
    .enablePlugins(CloudflowFlinkPlugin)
    .settings(
      Test / parallelExecution := false,
      Test / fork := true
    )

lazy val step2 = appModule("step2")
    .enablePlugins(CloudflowFlinkPlugin)
    .settings(
      Test / parallelExecution := false,
      Test / fork := true
    )

// [info] * org.scala-lang:scala-library:2.12.11
// [info] * org.apache.avro:avro:1.8.2
// [info] * com.google.protobuf:protobuf-java:3.11.4
// [info] * com.lightbend.akka.grpc:akka-grpc-runtime_2.12:1.0.2
// [info] * com.lightbend.akka.grpc:akka-grpc-runtime_2.12:1.0.2
// [info] * io.grpc:grpc-stub:1.32.1
// [info] * com.lightbend.akka.grpc:akka-grpc-runtime_2.12:1.0.2
// [info] * com.twitter:bijection-avro_2.12:0.9.7
// [info] * org.apache.avro:avro:1.8.2
// [info] * com.lightbend.cloudflow:cloudflow-runner_2.12:2.1.2
// [info] * com.lightbend.cloudflow:cloudflow-localrunner_2.12:2.1.2
// [info] * com.lightbend.cloudflow:cloudflow-flink_2.12:2.1.2
// [info] * com.lightbend.cloudflow:cloudflow-flink-testkit_2.12:2.1.2:test
// [info] * org.scalatest:scalatest:3.0.8:test
// [info] * junit:junit:4.12:test

lazy val step3 = appModule("step3")
    .enablePlugins(CloudflowFlinkPlugin)
    .settings(
      dependencyOverrides += "com.lightbend.akka.grpc" %% "akka-grpc-runtime" % "1.0.3",
      Test / parallelExecution := false,
      Test / fork := true
    )

lazy val app = appModule("app")
    .enablePlugins(CloudflowApplicationPlugin)
    .settings(
      Test / parallelExecution := false,
      Test / fork := true
    )
    .dependsOn(step3)


lazy val commonSettings = Seq(
  organization := "com.lightbend.cloudflow",
  headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2020", "Lightbend Inc. <https://www.lightbend.com>")),
  scalaVersion := "2.12.11",
  javacOptions += "-Xlint:deprecation",
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

  libraryDependencies ++= Seq(
        "org.scalatest"          %% "scalatest"                 % "3.0.8"    % "test",
        "junit"                  % "junit"                      % "4.12"     % "test"),

  schemaCodeGenerator := SchemaCodeGenerator.Java,
  javacOptions ++= Seq("-Xlint:deprecation")
)

dynverSeparator in ThisBuild := "-"

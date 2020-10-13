lazy val root =
  Project(id = "root", base = file("."))
    .aggregate(
      step0,
      step1,
      step2,
      step3,
      app,
      datamodel)

def appModule(moduleID: String): Project = {
  Project(id = moduleID, base = file(moduleID))
    .settings(
      name := moduleID
    )
    .withId(moduleID)
    .settings(commonSettings)
}

lazy val step0 = appModule("step0")
    .enablePlugins(CloudflowAkkaPlugin)
    .settings(
      Test / parallelExecution := false,
      Test / fork := true
    )

lazy val step1 = appModule("step1")
    .enablePlugins(CloudflowAkkaPlugin)
    .settings(
      Test / parallelExecution := false,
      Test / fork := true
    )

lazy val step2 = appModule("step2")
    .enablePlugins(CloudflowAkkaPlugin)
    .settings(
      Test / parallelExecution := false,
      Test / fork := true
    )

lazy val step3 = appModule("step3")
    .enablePlugins(CloudflowAkkaPlugin)
    .settings(
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

// not used. Only to show avro configuration
//tag::avro-config[]
lazy val datamodel = (project in file("./my-cloudflow-library"))
  .enablePlugins(CloudflowLibraryPlugin)
  .settings(
    schemaCodeGenerator := SchemaCodeGenerator.Java,
    schemaPaths := Map(
      SchemaFormat.Avro -> "src/main/resources/avroschemas",
      SchemaFormat.Proto -> "src/main/resources/protobuf"
    )
  )
//end::avro-config[]

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

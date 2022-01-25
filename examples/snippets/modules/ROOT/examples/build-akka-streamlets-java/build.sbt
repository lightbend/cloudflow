lazy val root =
  Project(id = "root", base = file("."))
    .aggregate(
      //step0,
      //step1,
      //step2,
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
    // NOTE: Since the example is not complete here, it would fail verification of the blueprint
    // NOTE: The CloudflowLibraryPlugin is used instead of CloudflowAkkaPlugin purely to make the code compile
    // NOTE: In a normal project it is required to enable to CloudflowAkkaPlugin instead, see step3.
    .enablePlugins(CloudflowLibraryPlugin)
    .settings(
      Test / parallelExecution := false,
      Test / fork := true
    )

lazy val step1 = appModule("step1")
    // NOTE: Since the example is not complete here, it would fail verification of the blueprint
    // NOTE: The CloudflowLibraryPlugin is used instead of CloudflowAkkaPlugin purely to make the code compile
    // NOTE: In a normal project it is required to enable to CloudflowAkkaPlugin instead, see step3.
    .enablePlugins(CloudflowLibraryPlugin)
    .settings(
      Test / parallelExecution := false,
      Test / fork := true
    )

lazy val step2 = appModule("step2")
    // NOTE: Since the example is not complete here, it would fail verification of the blueprint
    // NOTE: The CloudflowLibraryPlugin is used instead of CloudflowAkkaPlugin purely to make the code compile
    // NOTE: In a normal project it is required to enable to CloudflowAkkaPlugin instead, see step3.
    .enablePlugins(CloudflowLibraryPlugin)
    .settings(
      Test / parallelExecution := false,
      Test / fork := true
    )

lazy val step3 = appModule("step3")
    .enablePlugins(CloudflowAkkaPlugin)
    .settings(
      libraryDependencies += Cloudflow.library.CloudflowAvro,
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
  .settings(
    libraryDependencies += Cloudflow.library.CloudflowAvro
  )
//end::avro-config[]

lazy val commonSettings = Seq(
  organization := "com.lightbend.cloudflow",
  headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2020", "Lightbend Inc. <https://www.lightbend.com>")),
  scalaVersion := "2.12.15",
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
  Compile / console / scalacOptions --= Seq("-Ywarn-unused", "-Ywarn-unused-import"),
  Test / console / scalacOptions := (Compile / console / scalacOptions).value,
  avroStringType := "String", 

  libraryDependencies ++= Seq(
        "org.scalatest"          %% "scalatest"                 % "3.0.8"    % "test",
        "junit"                  %  "junit"                     % "4.12"     % "test"
  ),

  javacOptions ++= Seq("-Xlint:deprecation")
)

ThisBuild / dynverSeparator := "-"

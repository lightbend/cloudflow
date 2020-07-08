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
    .enablePlugins(CloudflowSparkPlugin)
    .settings(
      Test / parallelExecution := false,
      Test / fork := true,
      cloudflowDockerParentImage := "lightbend/spark:2.0.5-cloudflow-spark-2.4.5-scala-2.12"
    )

lazy val step1 = appModule("step1")
    .enablePlugins(CloudflowSparkPlugin)
    .settings(
      Test / parallelExecution := false,
      Test / fork := true,
      cloudflowDockerParentImage := "lightbend/spark:2.0.5-cloudflow-spark-2.4.5-scala-2.12"
    )

lazy val step2 = appModule("step2")
    .enablePlugins(CloudflowSparkPlugin)
    .settings(
      Test / parallelExecution := false,
      Test / fork := true,
      cloudflowDockerParentImage := "lightbend/spark:2.0.5-cloudflow-spark-2.4.5-scala-2.12"
    )

lazy val step3 = appModule("step3")
    .enablePlugins(CloudflowSparkPlugin)
    .settings(
      Test / parallelExecution := false,
      Test / fork := true,
      cloudflowDockerParentImage := "lightbend/spark:2.0.5-cloudflow-spark-2.4.5-scala-2.12"
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
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.0.8"  % "test",
    "org.slf4j" % "slf4j-simple" % "1.7.30" % Test
  ),

  scalacOptions in (Compile, console) --= Seq("-Ywarn-unused", "-Ywarn-unused-import"),
  scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value
)


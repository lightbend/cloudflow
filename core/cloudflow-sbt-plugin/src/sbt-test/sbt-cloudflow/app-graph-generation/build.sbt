lazy val helloWorld = (project in file("."))
  .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin)
  .settings(
    scalaVersion := "2.13.8",
    name := "hello-world",
    version := "0.0.1",
    cloudflowDockerBaseImage := "adoptopenjdk/openjdk11:alpine",
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.2.10"
    )
  )

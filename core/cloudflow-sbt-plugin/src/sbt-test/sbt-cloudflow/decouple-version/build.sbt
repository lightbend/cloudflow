lazy val helloWorld = (project in file("."))
  .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin)
  .settings(
    scalaVersion := "2.12.15",
    cloudflowVersion := "2.0.19",
    name := "hello-world",
    // sbt 1.5: Do not fail when mixing versions of Cloudflow and dependencies
    evictionErrorLevel := Level.Info,
    libraryDependencies ++= Seq("ch.qos.logback" % "logback-classic" % "1.4.6"))

val checkCloudflowVersion = taskKey[Unit]("Testing the used version of cloudflow")
checkCloudflowVersion := {
  val data = ujson.read(file("target/hello-world.json"))

  val libraryVersion = data("spec")("library_version").str

  assert { libraryVersion == "2.0.19" }
}

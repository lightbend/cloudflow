lazy val helloWorld =  (project in file("."))
    .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin)
    .settings(
      scalaVersion := "2.12.11",
      cloudflowVersion := "2.0.19",
      name := "hello-world",

      libraryDependencies ++= Seq(
        "ch.qos.logback"         %  "logback-classic"           % "1.2.3"
      )
    )

val checkCloudflowVersion = taskKey[Unit]("Testing the used version of cloudflow")
checkCloudflowVersion := {
  val data = ujson.read(file("target/hello-world.json"))

  val libraryVersion = data("spec")("library_version").str

  assert { libraryVersion == "2.0.19" }
}

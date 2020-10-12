lazy val helloWorld =  (project in file("."))
    .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin)
    .settings(
      scalaVersion := "2.12.11",
      cloudflowVersion := "2.0.10",
      name := "hello-world",
      cloudflowAkkaBaseImage := Some("lightbend/akka-base:2.0.10-cloudflow-akka-2.6.9-scala-2.12"),

      libraryDependencies ++= Seq(
        "ch.qos.logback"         %  "logback-classic"           % "1.2.3"
      )
    )

val checkCloudflowVersion = taskKey[Unit]("Testing the used version of cloudflow")
checkCloudflowVersion := {
  val data = ujson.read(file("target/hello-world.json"))

  val libraryVersion = data("spec")("library_version").str

  assert { libraryVersion == "2.0.10" }
}

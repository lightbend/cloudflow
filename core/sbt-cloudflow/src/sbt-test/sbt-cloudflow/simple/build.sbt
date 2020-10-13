lazy val helloWorld =  (project in file("."))
    .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin)
    .settings(
      scalaVersion := "2.12.11",
      name := "hello-world",
      cloudflowAkkaBaseImage := Some("lightbend/akka-base:2.0.10-cloudflow-akka-2.6.9-scala-2.12"),

      libraryDependencies ++= Seq(
        "ch.qos.logback"         %  "logback-classic"           % "1.2.3"
      )
    )

val checkCRFile = taskKey[Unit]("Testing the CR file")
checkCRFile := {
  val data = ujson.read(file("target/hello-world.json"))

  val appId = data("spec")("app_id").str
  val appVersion = data("spec")("app_version").str

  assert { appId == "hello-world" }
  assert { !appVersion.contains("sha256") }
}

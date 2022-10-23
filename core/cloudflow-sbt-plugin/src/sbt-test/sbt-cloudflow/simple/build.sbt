lazy val helloWorld =  (project in file("."))
    .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin)
    .settings(
      scalaVersion := "2.12.17",
      name := "hello-world",
      version := "0.0.1",

      libraryDependencies ++= Seq(
        "ch.qos.logback"         %  "logback-classic"           % "1.2.11"
      )
    )

val checkCRFile = taskKey[Unit]("Testing the CR file")
checkCRFile := {
  val data = ujson.read(file("target/hello-world.json"))

  val appId = data("spec")("app_id").str
  val appVersion = data("spec")("app_version").str
  
  val image = data("spec")("deployments")(0)("image").str

  assert { appId == "hello-world" }
  assert { !appVersion.contains("sha256") }
  assert { image == "hello-world:0.0.1"}
}

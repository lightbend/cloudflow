lazy val helloWorld =  (project in file("."))
    .enablePlugins(CloudflowApplicationPlugin, CloudflowFlinkPlugin)
    .settings(
      scalaVersion := "2.12.11",
      name := "hello-world",
      version := "0.0.1",
      cloudflowFlinkBaseImage := Some("lightbend/flink:2.0.10-cloudflow-flink-1.10.0-scala-2.12"),

      libraryDependencies ++= Seq(
        "ch.qos.logback"         %  "logback-classic"           % "1.2.3"
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

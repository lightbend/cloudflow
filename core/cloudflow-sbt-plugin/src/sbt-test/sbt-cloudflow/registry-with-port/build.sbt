lazy val helloWorld = (project in file("."))
  .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin)
  .settings(
    scalaVersion := "2.12.15",
    name := "hello-world",
    version := "0.0.1",
    cloudflowDockerImageName := Some(cloudflow.sbt.DockerImageName(s"localhost:5000/hello-world", "0.0.1")),
    libraryDependencies ++= Seq("ch.qos.logback" % "logback-classic" % "1.2.12"))

val checkCRFile = taskKey[Unit]("Testing the CR file")
checkCRFile := {
  val data = ujson.read(file("target/hello-world.json"))
  val image = data("spec")("deployments")(0)("image").str

  assert { image == "localhost:5000/hello-world:0.0.1" }
}

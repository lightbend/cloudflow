val version1 = "1.2.3-SNAPSHOT"
val version2 = "10.0.0"

lazy val helloWorld1 = (project in file("helloworld1"))
  .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin)
  .settings(
    version := version1,
    scalaVersion := "2.12.15",
    name := "hello-world-1",
    libraryDependencies ++= Seq("ch.qos.logback" % "logback-classic" % "1.2.12"))

lazy val helloWorld2 = (project in file("helloworld2"))
  .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin)
  .settings(
    version := version2,
    scalaVersion := "2.12.15",
    name := "hello-world-2",
    libraryDependencies ++= Seq("ch.qos.logback" % "logback-classic" % "1.2.12"))

lazy val root = (project in file("root")).aggregate(helloWorld1, helloWorld2)

val checkVersions = taskKey[Unit]("Testing the versions of the produced apps")
checkVersions := {
  val appVersion1 = ujson.read(file("target/hello-world-1.json"))("spec")("app_version").str
  val appVersion2 = ujson.read(file("target/hello-world-2.json"))("spec")("app_version").str

  assert { appVersion1 == version1 }
  assert { appVersion2 == version2 }
}

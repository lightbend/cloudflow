lazy val sensorData =  (project in file("."))
    .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin)
    .settings(
      scalaVersion := "2.12.11",
      name := "sensor-data-scala",

      libraryDependencies ++= Seq(
        "com.lightbend.akka"     %% "akka-stream-alpakka-file"  % "1.1.2",
        "com.typesafe.akka"      %% "akka-http-spray-json"      % "10.1.12",
        "ch.qos.logback"         %  "logback-classic"           % "1.2.3",
        "com.typesafe.akka"      %% "akka-http-testkit"         % "10.1.12" % "test",
        "org.scalatest"          %% "scalatest"                 % "3.0.8"  % "test"
      )
    )

val checkCRFile = taskKey[Unit]("Testing the CR file")
checkCRFile := {
  file("target/sensor-data-scala.json")
  val data = ujson.read(file("target/sensor-data-scala.json"))

  val appId = data("spec")("app_id").str
  val appVersion = data("spec")("app_version").str

  assert { appId == "sensor-data-scala" }
  assert { !appVersion.contains("sha256") }
}

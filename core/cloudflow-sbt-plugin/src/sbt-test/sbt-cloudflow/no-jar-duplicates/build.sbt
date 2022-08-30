lazy val helloWorld =  (project in file("."))
    .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin)
    .settings(
      scalaVersion := "2.12.15",
      name := "hello-world",
      version := "0.0.1",

      libraryDependencies ++= Seq(
        "ch.qos.logback"         %  "logback-classic"           % "1.4.0"
      )
    )

val checkDuplicates = taskKey[Unit]("Testing there are no duplicate jars in the final image")
checkDuplicates := {
  import scala.sys.process._

  val res = (Seq("docker", "run", "--rm", "--entrypoint=sh", "hello-world:0.0.2", "-c", "ls /opt/cloudflow") #| Seq("grep", "hello-world")).!!

  assert { res.trim == "hello-world_2.12-0.0.2.jar" }
}

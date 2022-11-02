lazy val helloWorld = (project in file("hello-world-logback"))
    .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin)
    .settings(
      scalaVersion := "2.12.15",
      name := "hello-world-logback",
      version := "0.0.1",
    )
cloudflowDockerRegistry in ThisBuild := Some(sys.env("DOCKER_REGISTRY"))
cloudflowDockerRepository in ThisBuild := Some(sys.env("DOCKER_REPOSITORY"))

val testLibraryVersion = {
  if (sys.props.get("scripted").isDefined) {
    sys.props("library.version")
  } else {
    sys.env.get("AKKA_PLATFORM_VERSION").getOrElse("latest")
  }
}

lazy val iTest = (project in file("it-test"))
    .settings(
      scalaVersion := "2.13.10",
      libraryDependencies ++= Seq(
        "com.lightbend.cloudflow" %% "cloudflow-new-it-library" % testLibraryVersion % Test,
        "com.lightbend.cloudflow" %% "kubectl-cloudflow" % testLibraryVersion % Test
      )
    )

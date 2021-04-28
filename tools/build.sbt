Global / cancelable := true

lazy val tooling =
  Project(id = "tooling", base = file("tooling"))
    .dependsOn(cloudflowCli)
    .settings(scalaVersion := Dependencies.Scala213)

lazy val cloudflowCrd =
  Project(id = "cloudflow-crd", base = file("cloudflow-crd"))
    .settings(Dependencies.cloudflowCrd)
    .settings(
      name := "cloudflow-crd",
      scalaVersion := Dependencies.Scala213,
      // make version compatible with docker for publishing
      ThisBuild / dynverSeparator := "-",
      Defaults.itSettings)

lazy val cloudflowConfig =
  Project(id = "cloudflow-config", base = file("cloudflow-config"))
    .settings(Dependencies.cloudflowConfig)
    .settings(
      name := "cloudflow-config",
      scalaVersion := Dependencies.Scala213,
      // make version compatible with docker for publishing
      ThisBuild / dynverSeparator := "-")
    .dependsOn(cloudflowCrd)

val getMuslBundle = taskKey[Unit]("Fetch Musl bundle")
val winPackageBin = taskKey[Unit]("PackageBin Graal on Windows")

lazy val cloudflowCli =
  Project(id = "cloudflow-cli", base = file("cloudflow-cli"))
    .settings(Dependencies.cloudflowCli)
    .settings(name := "kubectl-cloudflow")
    .settings(
      scalaVersion := Dependencies.Scala213,
      Compile / mainClass := Some("akka.cli.cloudflow.Main"),
      Compile / discoveredMainClasses := Seq(),
      // make version compatible with docker for publishing
      ThisBuild / dynverSeparator := "-",
      run / fork := true,
      getMuslBundle := {
        if (!((ThisProject / baseDirectory).value / "src" / "graal" / "bundle").exists && graalVMNativeImageGraalVersion.value.isDefined) {
          TarDownloader.downloadAndExtract(
            new URL("https://github.com/gradinac/musl-bundle-example/releases/download/v1.0/musl.tar.gz"),
            (ThisProject / baseDirectory).value / "src" / "graal")
        }
      },
      GraalVMNativeImage / packageBin := {
        if (graalVMNativeImageGraalVersion.value.isDefined) {
          (GraalVMNativeImage / packageBin).dependsOn(getMuslBundle).value
        } else {
          (GraalVMNativeImage / packageBin).value
        }
      },
      graalVMNativeImageOptions := Seq(
          "--verbose",
          "--no-server",
          "--enable-http",
          "--enable-https",
          "--enable-url-protocols=http,https,file,jar",
          "--enable-all-security-services",
          "-H:+JNI",
          "-H:IncludeResourceBundles=com.sun.org.apache.xerces.internal.impl.msg.XMLMessages",
          "-H:+ReportExceptionStackTraces",
          "--no-fallback",
          "--initialize-at-build-time",
          "--report-unsupported-elements-at-runtime",
          // TODO: possibly to be removed
          "--allow-incomplete-classpath",
          "--initialize-at-run-time" + Seq(
            "akka.cloudflow.config.CloudflowConfig$",
            "akka.cloudflow.config.UnsafeCloudflowConfigLoader$",
            "com.typesafe.config.impl.ConfigImpl",
            "com.typesafe.config.impl.ConfigImpl$EnvVariablesHolder",
            "com.typesafe.config.impl.ConfigImpl$SystemPropertiesHolder",
            "com.typesafe.config.impl.ConfigImpl$LoaderCacheHolder",
            "io.fabric8.kubernetes.client.internal.CertUtils$1").mkString("=", ",", "")),
      GraalVMNativeImage / winPackageBin := {
        val targetDirectory = target.value
        val binaryName = name.value
        val nativeImageCommand = graalVMNativeImageCommand.value
        val className = (Compile / mainClass).value.getOrElse(sys.error("Could not find a main class."))
        val classpathJars = scriptClasspathOrdering.value
        val extraOptions = graalVMNativeImageOptions.value
        val streams = Keys.streams.value
        val dockerCommand = DockerPlugin.autoImport.dockerExecCommand.value

        targetDirectory.mkdirs()
        val temp = IO.createTemporaryDirectory

        try {
          classpathJars.foreach {
            case (f, _) =>
              IO.copyFile(f, (temp / f.getName))
          }

          val command = {
            val nativeImageArguments = {
              Seq("--class-path", s""""${(temp / "*").getAbsolutePath}"""", s"-H:Name=$binaryName") ++ extraOptions ++ Seq(
                className)
            }
            Seq(nativeImageCommand) ++ nativeImageArguments
          }

          (sys.process.Process(command, targetDirectory).!) match {
            case 0 => targetDirectory / binaryName
            case x => sys.error(s"Failed to run $command, exit status: " + x)
          }
        } finally {
          temp.delete()
        }
      })
    .enablePlugins(BuildInfoPlugin, GraalVMNativeImagePlugin)
    .dependsOn(cloudflowConfig, cloudflowRunnerConfig)

lazy val cloudflowIt =
  Project(id = "cloudflow-it", base = file("cloudflow-it"))
    .configs(IntegrationTest.extend(Test))
    .settings(Defaults.itSettings, Dependencies.cloudflowIt)
    .settings(
      scalaVersion := Dependencies.Scala213,
      inConfig(IntegrationTest)(org.scalafmt.sbt.ScalafmtPlugin.scalafmtConfigSettings),
      IntegrationTest / fork := true)
    .dependsOn(cloudflowCli)

lazy val cloudflowNewItLibrary =
  Project(id = "cloudflow-new-it-library", base = file("cloudflow-new-it-library"))
    .settings(Dependencies.cloudflowNewItLibrary)
    .settings(scalaVersion := Dependencies.Scala213)
    .dependsOn(cloudflowCli)

lazy val cloudflowNewIt =
  Project(id = "cloudflow-new-it", base = file("cloudflow-new-it"))
    .settings(
      scalaVersion := Dependencies.Scala212,
      scriptedLaunchOpts := {
        scriptedLaunchOpts.value ++
        Seq(
          "-Xmx1024M",
          "-Dscripted=true",
          "-Dcloudflow.version=" + sys.env.get("CLOUDFLOW_VERSION").getOrElse("not-defined-cloudflow-version"),
          "-Dlibrary.version=" + version.value)
      },
      scriptedBufferLog := false,
      scriptedDependencies := {
        // This cleanup the directories for local development
        import scala.sys.process._
        val ignoredFiles = "git status --ignored --porcelain".!!
        if (!ignoredFiles.isEmpty) {
          IO.delete(
            ignoredFiles
              .split("\n")
              .filter(_.startsWith("!! cloudflow-new-it/src/sbt-test"))
              .map { f => file(f.replaceFirst("!! ", "")) })
        }

        (ThisProject / scriptedDependencies).value
        (cloudflowCrd / publishLocal).value
        (cloudflowConfig / publishLocal).value
        (cloudflowCli / publishLocal).value
        (cloudflowNewItLibrary / publishLocal).value
      },
      // the following settings are to run the tests in parallel
      // tuned to run against a real cluster (for now)
      scriptedBatchExecution := true,
      scriptedParallelInstances := 1)
    .enablePlugins(ScriptedPlugin)

lazy val setVersionFromTag = taskKey[Unit]("Set a stable version from env variable")

setVersionFromTag := {
  IO.write(file("version.sbt"), s"""ThisBuild / version := "${sys.env
    .get("VERSION")
    .getOrElse("0.0.0-SNAPSHOT")}"""")
}

// makePom fails, often with: java.lang.StringIndexOutOfBoundsException: String index out of range: 0
addCommandAlias(
  "winGraalBuild",
  s"""project cloudflow-cli; set makePom / publishArtifact := false; set graalVMNativeImageCommand := "${sys.env
    .get("JAVA_HOME")
    .getOrElse("")
    .replace("""\""", """\\\\""")}\\\\bin\\\\native-image.cmd"; graalvm-native-image:winPackageBin""")

addCommandAlias(
  "linuxStaticBuild",
  """project cloudflow-cli; set graalVMNativeImageGraalVersion := Some("20.1.0-java11"); set graalVMNativeImageOptions ++= Seq("--static", "-H:UseMuslC=/opt/graalvm/stage/resources/bundle/"); graalvm-native-image:packageBin""")

addCommandAlias(
  "regenerateGraalVMConfig",
  s""";project tooling ; set run / fork := true; set run / javaOptions += "-agentlib:native-image-agent=config-output-dir=${file(
    ".").getAbsolutePath}/cloudflow-cli/src/main/resources/META-INF/native-image"; runMain cli.CodepathCoverageMain""")

lazy val cloudflowBlueprint =
  Project(id = "cloudflow-blueprint", base = file("cloudflow-blueprint"))
    .enablePlugins(BuildInfoPlugin, ScalafmtPlugin)
    .settings(Dependencies.cloudflowBlueprint)
    .settings(
      scalaVersion := Dependencies.Scala213,
      crossScalaVersions := Vector(Dependencies.Scala212, Dependencies.Scala213),
      scalafmtOnCompile := true,
      buildInfoKeys := Seq[BuildInfoKey](name, version),
      buildInfoPackage := "cloudflow.blueprint")
    .cross

lazy val cloudflowBlueprint212 = cloudflowBlueprint(Dependencies.Scala212)
lazy val cloudflowBlueprint213 = cloudflowBlueprint(Dependencies.Scala213)

lazy val cloudflowOperator =
  Project(id = "cloudflow-operator", base = file("cloudflow-operator"))
    .enablePlugins(ScalafmtPlugin, BuildInfoPlugin, JavaServerAppPackaging, DockerPlugin, AshScriptPlugin)
    .dependsOn(cloudflowConfig, cloudflowBlueprint213)
    .settings(Dependencies.cloudflowOperator)
    .settings(
      scalaVersion := Dependencies.Scala213,
      scalafmtOnCompile := true,
      run / fork := true,
      Global / cancelable := true,
      buildInfoKeys := Seq[BuildInfoKey](
          name,
          version,
          scalaVersion,
          sbtVersion,
          BuildInfoKey.action("buildTime") {
            java.time.Instant.now().toString
          },
          BuildInfoKey.action("buildUser") {
            sys.props.getOrElse("user.name", "unknown")
          }),
      buildInfoPackage := "cloudflow.operator")
    .settings(
      Docker / packageName := "cloudflow-operator",
      dockerUpdateLatest := false,
      dockerUsername := sys.props.get("docker.username"),
      dockerRepository := sys.props.get("docker.registry"),
      dockerBaseImage := "adoptopenjdk/openjdk11:alpine-jre")

lazy val cloudflowExtractor =
  Project(id = "cloudflow-extractor", base = file("cloudflow-extractor"))
    .enablePlugins(ScalafmtPlugin, BuildInfoPlugin)
    .settings(Dependencies.cloudflowExtractor)
    .settings(
      scalaVersion := Dependencies.Scala212,
      scalafmtOnCompile := true,
      run / fork := true,
      Global / cancelable := true)

lazy val cloudflowSbtPlugin =
  Project(id = "cloudflow-sbt-plugin", base = file("cloudflow-sbt-plugin"))
    .settings(name := "sbt-cloudflow")
    .dependsOn(cloudflowBlueprint212, cloudflowExtractor)
    .enablePlugins(BuildInfoPlugin, ScalafmtPlugin, SbtPlugin)
    .settings(Dependencies.cloudflowSbtPlugin)
    .settings(
      scalaVersion := Dependencies.Scala212,
      scalafmtOnCompile := true,
      sbtPlugin := true,
      crossSbtVersions := Vector("1.4.9"),
      buildInfoKeys := Seq[BuildInfoKey](version),
      buildInfoPackage := "cloudflow.sbt",
      addSbtPlugin("se.marcuslonnberg" % "sbt-docker" % "1.8.2"),
      addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.25"),
      addSbtPlugin("com.cavorite" % "sbt-avro-1-8" % "1.1.9"),
      addSbtPlugin("com.lightbend.akka.grpc" % "sbt-akka-grpc" % Dependencies.Versions.akkaGrpc),
      addSbtPlugin("com.julianpeeters" % "sbt-avrohugger" % "2.0.0-RC18"),
      addSbtPlugin("com.lightbend.sbt" % "sbt-javaagent" % "0.1.5"),
      addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.2.0"),
      scriptedLaunchOpts := {
        scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
      },
      scriptedBufferLog := false)

lazy val cloudflowRunnerConfig =
  Project(id = "cloudflow-runner-config", base = file("cloudflow-runner-config"))
    .enablePlugins(BuildInfoPlugin, ScalafmtPlugin)
    .settings(Dependencies.cloudflowRunnerConfig)
    .settings(
      scalaVersion := Dependencies.Scala213,
      crossScalaVersions := Vector(Dependencies.Scala212, Dependencies.Scala213),
      scalafmtOnCompile := true)

lazy val root = Project(id = "root", base = file("."))
  .settings(name := "root", skip in publish := true, scalafmtOnCompile := true, crossScalaVersions := Seq())
  .withId("root")
  .aggregate(
    cloudflowBlueprint212,
    cloudflowBlueprint213,
    cloudflowCli,
    cloudflowConfig,
    cloudflowCrd,
    cloudflowExtractor,
    cloudflowIt,
    cloudflowNewIt,
    cloudflowNewItLibrary,
    cloudflowOperator,
    cloudflowSbtPlugin,
    cloudflowRunnerConfig,
    tooling)

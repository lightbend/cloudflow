Global / cancelable := true

ThisBuild / resolvers += Resolver.mavenLocal

lazy val tooling =
  Project(id = "tooling", base = file("tooling"))
    .dependsOn(cloudflowCli)

lazy val cloudflowCrd =
  Project(id = "cloudflow-crd", base = file("cloudflow-crd"))
    .settings(Dependencies.cloudflowCrd)
    .settings(
      name := "cloudflow-crd",
      // make version compatible with docker for publishing
      ThisBuild / dynverSeparator := "-",
      Defaults.itSettings)

val getMuslBundle = taskKey[Unit]("Fetch Musl bundle")
val winPackageBin = taskKey[Unit]("PackageBin Graal on Windows")

lazy val cloudflowCli =
  Project(id = "cloudflow-cli", base = file("cloudflow-cli"))
    .settings(Dependencies.cloudflowCli)
    .settings(name := "kubectl-cloudflow")
    .settings(
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
    .dependsOn(cloudflowCrd)

lazy val cloudflowIt =
  Project(id = "cloudflow-it", base = file("cloudflow-it"))
    .configs(IntegrationTest.extend(Test))
    .settings(Defaults.itSettings, Dependencies.cloudflowIt)
    .settings(
      inConfig(IntegrationTest)(org.scalafmt.sbt.ScalafmtPlugin.scalafmtConfigSettings),
      IntegrationTest / fork := true)
    .dependsOn(cloudflowCli)

lazy val cloudflowNewItLibrary =
  Project(id = "cloudflow-new-it-library", base = file("cloudflow-new-it-library"))
    .settings(Dependencies.cloudflowNewItLibrary)
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
        (cloudflowCli / publishLocal).value
        (cloudflowNewItLibrary / publishLocal).value
      },
      // the following settings are to run the tests in parallel
      // tuned to run against a real cluster (for now)
      scriptedBatchExecution := true,
      scriptedParallelInstances := 1)
    .enablePlugins(ScriptedPlugin)

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

import sbt._
import sbt.Keys._

import scalariform.formatter.preferences._
import Library._

import sbtrelease.ReleaseStateTransformations._

lazy val root =
  Project(id = "root", base = file("."))
    .enablePlugins(ScalaUnidocPlugin, JavaUnidocPlugin)
    .settings(
      name := "root",
      skip in publish := true,
      commands += InternalReleaseCommand.command,
      unidocProjectFilter in (ScalaUnidoc, unidoc) := inProjects(
        streamlets,
        akkastream,
        akkastreamUtil,
        akkastreamTestkit,
        spark,
        sparkTestkit
      )
    )
    .withId("root")
    .settings(commonSettings)
    .aggregate(
      streamlets,
      events,
      akkastream,
      akkastreamUtil,
      akkastreamTestkit,
      akkastreamTests,
      spark,
      sparkTestkit,
      sparkTests,
      flink,
      flinkTestkit,
      flinkTests,
      runner,
      blueprint,
      plugin,
      operator
    )

lazy val streamlets =
  cloudflowModule("cloudflow-streamlets")
    .enablePlugins(GenJavadocPlugin)
    .settings(
      libraryDependencies ++= Vector(
        SprayJson,
        Ficus,
        Bijection,
        ScalaTest
      )
    )

lazy val events =
  cloudflowModule("cloudflow-events")
    .enablePlugins(BuildInfoPlugin)
    .dependsOn(streamlets)
    .settings(
      libraryDependencies ++= Vector(
        AkkaStream,
        Ficus,
        Skuber,
        Logback % Test,
        ScalaTest,
        MockitoScala
      )
    )

lazy val akkastream =
  cloudflowModule("cloudflow-akka")
    .enablePlugins(GenJavadocPlugin)
    .dependsOn(streamlets)
    .settings(
      libraryDependencies ++= Vector(
        AkkaStream,
        AkkaStreamKafka,
        SprayJson,
        JacksonScalaModule,
        Ficus
      )
    )

lazy val akkastreamUtil =
  cloudflowModule("cloudflow-akka-util")
    .enablePlugins(GenJavadocPlugin)
    .dependsOn(akkastream, akkastreamTestkit)
    .settings(
      libraryDependencies ++= Vector(
        AkkaHttp,
        AkkaHttpJackson,
        AkkaStreamContrib,
        AkkaHttpTestkit,
        Logback % Test,
        AkkaHttpSprayJsonTest,
        Junit,
        ScalaTest
      )
    )
    .settings(
      (sourceGenerators in Test) += (avroScalaGenerateSpecific in Test).taskValue
    )

lazy val akkastreamTestkit =
  cloudflowModule("cloudflow-akka-testkit")
    .enablePlugins(GenJavadocPlugin)
    .dependsOn(akkastream)
    .settings(
      libraryDependencies ++= Vector(
        AkkaSlf4j,
        AkkaStream,
        AkkaStreamContrib,
        Ficus,
        Logback % Test,
        AkkaStreamTestkit,
        AkkaTestkit,
        ScalaTest,
        Junit
      )
    )
    .settings(
      (sourceDirectory in AvroConfig) := baseDirectory.value / "src/test/avro",
      (stringType in AvroConfig) := "String",
      javacOptions += "-Xlint:unchecked"
    )

lazy val akkastreamTests =
  cloudflowModule("cloudflow-akka-tests")
    .dependsOn(akkastream, akkastreamTestkit)
    .settings(
      libraryDependencies ++= Vector(
        AkkaHttpTestkit,
        AkkaHttpSprayJsonTest,
        Logback % Test,
        ScalaTest,
        Junit
      )
    )
    .settings(
      (sourceGenerators in Test) += (avroScalaGenerateSpecific in Test).taskValue
    )

lazy val spark =
  cloudflowModule("cloudflow-spark")
    .enablePlugins(GenJavadocPlugin)
    .dependsOn(streamlets)
    .settings(
      libraryDependencies ++= Seq(
        AkkaSlf4j,
        AkkaStream,
        AkkaStreamContrib,
        Ficus,
        Spark,
        SparkMllib,
        SparkSql,
        SparkSqlKafka,
        SparkStreaming,
        Logback % Test,
        ScalaTest
      ),
      libraryDependencies ~= { _.map(_.exclude("org.slf4j", "slf4j-log4j12")) }
    )
    .settings(
      (sourceGenerators in Test) += (avroScalaGenerateSpecific in Test).taskValue
    )

lazy val sparkTestkit =
  cloudflowModule("cloudflow-spark-testkit")
    .enablePlugins(GenJavadocPlugin)
    .dependsOn(spark)
    .settings(
      libraryDependencies ++= Vector(
        ScalaTestUnscoped,
        Junit
      )
    )

lazy val sparkTests =
  cloudflowModule("cloudflow-spark-tests")
    .dependsOn(sparkTestkit)
    .settings(
      libraryDependencies ++= Vector(
        Logback % Test,
        ScalaTest,
        Junit
      )
    )
    .settings(
      (sourceGenerators in Test) += (avroScalaGenerateSpecific in Test).taskValue
    )
    .settings(
      parallelExecution in Test := false
    )

lazy val flink =
  cloudflowModule("cloudflow-flink")
    .dependsOn(streamlets)
    .settings(
      libraryDependencies ++= Seq(
        Flink,
        FlinkStreaming,
        FlinkKafka,
        FlinkAvro,
        ScalaTest
      ),
      libraryDependencies ~= { _.map(_.exclude("org.slf4j", "slf4j-log4j12")) }
    )
    .settings(
      (sourceGenerators in Test) += (avroScalaGenerateSpecific in Test).taskValue
    )

lazy val flinkTestkit =
  cloudflowModule("cloudflow-flink-testkit")
    .dependsOn(flink)
    .settings(
      libraryDependencies ++= Vector(
        ScalaTestUnscoped,
        Logback % Test,
        Junit
      )
    )

lazy val flinkTests =
  cloudflowModule("cloudflow-flink-tests")
    .dependsOn(flinkTestkit)
    .settings(
      libraryDependencies ++= Vector(
        FlinkAvro,
        Logback % Test,
        ScalaTest,
        Junit,
        JUnitInterface
      )
    )
    .settings(
      (sourceGenerators in Test) += (avroScalaGenerateSpecific in Test).taskValue
    )
    .settings(
      parallelExecution in Test := false
    )

lazy val blueprint =
  cloudflowModule("cloudflow-blueprint")
    .enablePlugins(BuildInfoPlugin)
    .settings(
      libraryDependencies ++= Vector(
        SprayJson,
        Config,
        Logback % Test,
        Avro4sTest,
        ScalaTest
      ),

      publishArtifact in Test := true,
    )
    .settings(
      buildInfoKeys := Seq[BuildInfoKey](
        name,
        version
      ),
      buildInfoPackage := "cloudflow.blueprint"
    )

lazy val plugin =
  cloudflowModule("sbt-cloudflow")
    .dependsOn(streamlets, blueprint)
    .enablePlugins(BuildInfoPlugin)
    .settings(
      sbtPlugin := true,
      crossSbtVersions := Vector("1.2.8"),

      buildInfoKeys := Seq[BuildInfoKey](version),
      buildInfoPackage := "cloudflow.sbt",

      addSbtPlugin("se.marcuslonnberg" % "sbt-docker" % "1.5.0"),
      addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.25"),
      addSbtPlugin("com.cavorite" % "sbt-avro-1-8" % "1.1.9"),
      addSbtPlugin("com.julianpeeters" % "sbt-avrohugger" % "2.0.0-RC18"),
      addSbtPlugin("com.lightbend.sbt" % "sbt-javaagent" % "0.1.5"),
      addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.2.0"),

      libraryDependencies ++= Vector(
        AkkaHttp,
        AkkaHttpSprayJson,
        AkkaStream,
        FastClasspathScanner,
        Logback % Test,
        ScalaTest
      ),
    )

lazy val runner =
  cloudflowModule("cloudflow-runner")
    .enablePlugins(BuildInfoPlugin)
    .dependsOn(streamlets, blueprint, events)
    .settings(
      libraryDependencies ++= Vector(
        Ficus,
        EmbeddedKafka
      )
    )
    .settings(
      artifactName in (Compile, packageBin) := { (sv: ScalaVersion, module: ModuleID, artifact: Artifact) =>
        "runner" + "." + artifact.extension
      },
      crossPaths := false
    )
    .settings(
      buildInfoKeys := Seq[BuildInfoKey](name,
        version,
        scalaVersion,
        sbtVersion,
        BuildInfoKey.action("buildTime") {
          java.time.Instant.now().toString
        },
        BuildInfoKey.action("buildUser") {
          sys.props.getOrElse("user.name", "unknown")
        }
      ),
      buildInfoPackage := "cloudflow.runner"
    )

lazy val operator =
  cloudflowModule("cloudflow-operator")
    .enablePlugins(
      sbtdocker.DockerPlugin,
      JavaAppPackaging,
      BuildNumberPlugin,
      BuildInfoPlugin
    )
    .dependsOn(blueprint % "compile->compile;test->test")
    .settings(
      libraryDependencies ++= Vector(
        AkkaSlf4j,
        AkkaStream,
        Ficus,
        Logback,
        Skuber,
        AkkaStreamTestkit,
        ScalaTest,
        ScalaCheck              % "test",
        Avro4sJson              % "test",
      )
    )
    .settings(
      scalaVersion := "2.12.9",
      crossScalaVersions := Vector(scalaVersion.value),
      organization := "com.lightbend.cloudflow",

      skip in publish := true,

      mainClass in Compile := Some("cloudflow.operator.Main"),

      publishArtifact in (Compile, packageDoc) := false,
      publishArtifact in (Compile, packageSrc) := false,

      buildOptions in docker := BuildOptions(
        cache = true,
        removeIntermediateContainers = BuildOptions.Remove.OnSuccess,
        pullBaseImage = BuildOptions.Pull.IfMissing
        // TODO: "Always" won't work unless you have used `docker login` with
        // your own service/user account. We should move to use some
        // way to call `gcloud docker --` instead.
        // pullBaseImage = BuildOptions.Pull.Always
      ),

      imageNames in docker := Seq(
        ImageName(
          registry = None,
          namespace = Some("lightbend"),
          repository = "cloudflow-operator",
          tag = Some(cloudflowBuildNumber.value.asVersion)
        )
      ),

      dockerfile in docker := {
        val appDir: File = stage.value
        val targetDir = "/app"

        new Dockerfile {
          from("adoptopenjdk/openjdk8:latest")
          entryPoint(s"$targetDir/bin/${executableScriptName.value}")
          copy(appDir, targetDir, chown = "daemon:daemon")
        }
      },

      Test / fork := true,

      scalacOptions ++= Seq(
        "-encoding", "UTF-8",
        "-target:jvm-1.8",
        "-Xlog-reflective-calls",
        "-Xlint",
        "-Ywarn-unused",
        "-Ywarn-unused-import",
        "-deprecation",
        "-feature",
        "-language:_",
        "-unchecked"
      ),

      scalacOptions in (Compile, console) := (scalacOptions in (Global)).value.filter(_ == "-Ywarn-unused-import"),
      scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value,

      scalariformPreferences := scalariformPreferences.value
        .setPreference(AlignParameters, false)
        .setPreference(AlignSingleLineCaseStatements, true)
        .setPreference(AlignSingleLineCaseStatements.MaxArrowIndent, 90)
        .setPreference(DoubleIndentConstructorArguments, true)
        .setPreference(DoubleIndentMethodDeclaration, true)
        .setPreference(RewriteArrowSymbols, true)
        .setPreference(DanglingCloseParenthesis, Preserve)
        .setPreference(NewlineAtEndOfFile, true)
        .setPreference(AllowParamGroupsOnNewlines, true)
    )
    .settings(
      buildInfoKeys := Seq[BuildInfoKey](name,
        version,
        scalaVersion,
        sbtVersion,
        BuildInfoKey.action("buildTime") {
          java.time.Instant.now().toString
        },
        BuildInfoKey.action("buildUser") {
          sys.props.getOrElse("user.name", "unknown")
        }
      ),
      buildInfoPackage := "cloudflow.operator"
    )

def cloudflowModule(moduleID: String): Project = {
  Project(id = moduleID, base = file(moduleID))
    .settings(
      name := moduleID
    )
    .withId(moduleID)
    .settings(commonSettings)
    .settings(formattingSettings)
    .enablePlugins(AutomateHeaderPlugin)
}

lazy val commonSettings = Seq(
  organization := "com.lightbend.cloudflow",
  headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2019", "Lightbend Inc. <https://www.lightbend.com>")),
  scalaVersion := Version.Scala,
  autoAPIMappings := true,
  useGpgAgent := false,

  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    releaseStepCommandAndRemaining("publishSigned"),
    releaseStepCommand("sonatypeBundleRelease"),
    setNextVersion,
    commitNextVersion,
    pushChanges
  ),

  unidocGenjavadocVersion := "0.13",

  scalacOptions ++= Seq(
    "-encoding", "UTF-8",
    "-target:jvm-1.8",
    "-Xlog-reflective-calls",
    "-Xlint",
    "-Ywarn-unused",
    "-Ywarn-unused-import",
    "-deprecation",
    "-feature",
    "-language:_",
    "-unchecked"
  ),

  resolvers += "Akka Snapshots" at "https://repo.akka.io/snapshots/",
  scalacOptions in (Compile, console) := (scalacOptions in (Global)).value.filter(_ == "-Ywarn-unused-import"),
  scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value,
)

lazy val formattingSettings = Seq(
  scalariformPreferences := scalariformPreferences.value
    .setPreference(AlignParameters, false)
    .setPreference(AlignSingleLineCaseStatements, true)
    .setPreference(AlignSingleLineCaseStatements.MaxArrowIndent, 90)
    .setPreference(DoubleIndentConstructorArguments, true)
    .setPreference(DoubleIndentMethodDeclaration, true)
    .setPreference(RewriteArrowSymbols, true)
    .setPreference(DanglingCloseParenthesis, Preserve)
    .setPreference(NewlineAtEndOfFile, true)
    .setPreference(AllowParamGroupsOnNewlines, true)
)

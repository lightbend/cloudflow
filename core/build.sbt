import sbt._
import sbt.Keys._
import Library._
import sbtdocker.Instructions
import sbtrelease.ReleaseStateTransformations._

val javadocDisabledFor = Set(
  // link to URL is not correctly mapped by genjavadoc (https://github.com/lightbend/genjavadoc/issues/43#issuecomment-60261931)
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/RegExpConfigParameter$.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/DurationConfigParameter$.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/MemorySizeConfigParameter$.java",
  // '@throws' in scaladoc but there is now 'throws' clause on the method
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/StreamletContext.java",
  "/cloudflow-akka/target/java/cloudflow/akkastream/AkkaStreamletLogic.java",
  "/cloudflow-spark/target/java/cloudflow/spark/SparkStreamletLogic.java",
  // from JDK 11 failures
  "/cloudflow-akka-testkit/target/java/cloudflow/akkastream/testkit/TestContext$.java",
  "/cloudflow-akka-testkit/target/java/cloudflow/akkastream/testkit/TestContext.java",
  "/cloudflow-akka-testkit/target/java/cloudflow/akkastream/testkit/TestContextException$.java",
  "/cloudflow-akka-testkit/target/java/cloudflow/akkastream/testkit/TestContextException.java",
  "/cloudflow-akka-testkit/target/java/cloudflow/akkastream/testkit/javadsl/Completed.java",
  "/cloudflow-akka-testkit/target/java/cloudflow/akkastream/testkit/javadsl/ConfigParameterValueImpl$.java",
  "/cloudflow-akka-testkit/target/java/cloudflow/akkastream/testkit/javadsl/ConfigParameterValueImpl.java",
  "/cloudflow-akka-testkit/target/java/cloudflow/akkastream/testkit/javadsl/Failed$.java",
  "/cloudflow-akka-testkit/target/java/cloudflow/akkastream/testkit/javadsl/Failed.java",
  "/cloudflow-akka-testkit/target/java/cloudflow/akkastream/testkit/scaladsl/Completed.java",
  "/cloudflow-akka-testkit/target/java/cloudflow/akkastream/testkit/scaladsl/ConfigParameterValueImpl$.java",
  "/cloudflow-akka-testkit/target/java/cloudflow/akkastream/testkit/scaladsl/ConfigParameterValueImpl.java",
  "/cloudflow-akka-testkit/target/java/cloudflow/akkastream/testkit/scaladsl/Failed$.java",
  "/cloudflow-akka-testkit/target/java/cloudflow/akkastream/testkit/scaladsl/Failed.java",
  "/cloudflow-akka/target/java/cloudflow/akkastream/AkkaStreamletRuntime.java",
  "/cloudflow-akka/target/java/cloudflow/akkastream/Earliest.java",
  "/cloudflow-akka/target/java/cloudflow/akkastream/Latest.java",
  "/cloudflow-spark-testkit/target/java/cloudflow/spark/testkit/ExecutionReport.java",
  "/cloudflow-spark-testkit/target/java/cloudflow/spark/testkit/QueryExecutionMonitor.java",
  "/cloudflow-spark-testkit/target/java/cloudflow/spark/testkit/SparkStreamletTestkit.java",
  "/cloudflow-spark-testkit/target/java/cloudflow/spark/testkit/TestContextException.java",
  "/cloudflow-spark/target/java/cloudflow/spark/SparkStreamletRuntime.java",
  "/cloudflow-spark/target/java/cloudflow/spark/kafka/EncodedKV.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/AkkaClusterAttribute.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/BooleanValidationType.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/BootstrapServersForTopicNotFound.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/DecodeException.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/DoubleValidationType.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/Dun.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/DurationValidationType.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/ExceptionAcc.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/IntegerValidationType.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/LoadedStreamlet.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/MemorySizeValidationType.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/PortMapping.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/ReadOnlyMany.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/ReadWriteMany.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/RegexpValidationType.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/SchemaDefinition.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/ServerAttribute.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/StreamletContextData.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/StreamletLoader.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/StreamletShapeImpl.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/TopicForPortNotFoundException.java",
  "/cloudflow-streamlets/target/java/cloudflow/streamlets/descriptors/VolumeMountDescriptor.java",
  "/cloudflow/cloudflow/core/cloudflow-spark/target/java/cloudflow/spark/kafka/EncodedKV.java"
)

lazy val root =
  Project(id = "root", base = file("."))
    .enablePlugins(ScalaUnidocPlugin, JavaUnidocPlugin, ScalafmtPlugin)
    .settings(
      name := "root",
      skip in publish := true,
      scalafmtOnCompile := true,
      crossScalaVersions := Seq(),
      commands += InternalReleaseCommand.command,
      unidocAllSources in (JavaUnidoc, unidoc) ~= { v =>
        v.map(_.filterNot(f => javadocDisabledFor.exists(f.getAbsolutePath.endsWith(_))))
      },
      ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(
            streamlets,
            akkastream,
            akkastreamUtil,
            akkastreamTestkit,
            spark,
            sparkTestkit
          ),
      JavaUnidoc / unidoc / unidocProjectFilter := (ScalaUnidoc / unidoc / unidocProjectFilter).value
    )
    .withId("root")
    .settings(commonSettings)
    .aggregate(
      streamlets,
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
      runnerConfig,
      localRunner,
      runner,
      blueprint
    )

lazy val streamlets =
  cloudflowModule("cloudflow-streamlets")
    .enablePlugins(GenJavadocPlugin, ScalafmtPlugin)
    .settings(
      crossScalaVersions := Vector(Version.Scala212, Version.Scala213),
      scalafmtOnCompile := true,
      libraryDependencies ++= Vector(
            SprayJson,
            Ficus,
            Avro,
            Bijection,
            ScalaPbRuntime,
            ScalaTest
          )
    )

lazy val akkastream =
  cloudflowModule("cloudflow-akka")
    .enablePlugins(GenJavadocPlugin, JavaFormatterPlugin, ScalafmtPlugin)
    .dependsOn(streamlets)
    .settings(
      crossScalaVersions := Vector(Version.Scala212, Version.Scala213),
      javacOptions += "-Xlint:deprecation",
      scalafmtOnCompile := true,
      libraryDependencies ++= Vector(
            AkkaSlf4j,
            AkkaStream,
            AkkaStreamKafka,
            AkkaStreamKafaSharding,
            AkkaShardingTyped,
            AkkaCluster,
            AkkaManagement,
            AkkaHttp,
            AkkaHttpSprayJson,
            AkkaClusterBootstrap,
            AkkaDiscovery,
            AkkaDiscoveryK8,
            LogbackClassic,
            LogbackCore,
            SprayJson,
            JacksonScalaModule,
            Ficus
          )
    )

lazy val akkastreamUtil =
  cloudflowModule("cloudflow-akka-util")
    .enablePlugins(GenJavadocPlugin, JavaFormatterPlugin, ScalafmtPlugin)
    .dependsOn(akkastream, akkastreamTestkit % Test)
    .settings(
      crossScalaVersions := Vector(Version.Scala212, Version.Scala213),
      scalafmtOnCompile := true,
      libraryDependencies ++= Vector(
            AkkaHttp,
            AkkaHttpJackson,
            AkkaHttp2Support,
            AkkaGrpcRuntime,
            AkkaStreamContrib,
            AkkaHttpTestkit,
            AkkaStreamTestkit,
            AkkaHttpSprayJsonTest,
            Junit,
            ScalaTest
          )
    )
    .settings(
      javacOptions += "-Xlint:deprecation",
      (sourceGenerators in Test) += (avroScalaGenerateSpecific in Test).taskValue
    )

lazy val akkastreamTestkit =
  cloudflowModule("cloudflow-akka-testkit")
    .enablePlugins(GenJavadocPlugin, JavaFormatterPlugin, ScalafmtPlugin)
    .dependsOn(akkastream)
    .settings(
      crossScalaVersions := Vector(Version.Scala212, Version.Scala213),
      scalafmtOnCompile := true,
      libraryDependencies ++= Vector(
            AkkaSlf4j,
            AkkaStream,
            AkkaStreamContrib,
            Ficus,
            AkkaStreamKafkaTestkit,
            AkkaStreamTestkit,
            AkkaTestkit,
            ScalaTest,
            Junit
          )
    )
    .settings(
      javacOptions += "-Xlint:deprecation",
      javacOptions += "-Xlint:unchecked"
    )

lazy val akkastreamTests =
  cloudflowModule("cloudflow-akka-tests")
    .enablePlugins(JavaFormatterPlugin, ScalafmtPlugin)
    .dependsOn(akkastream, akkastreamTestkit % Test)
    .settings(
      crossScalaVersions := Vector(Version.Scala212, Version.Scala213),
      scalafmtOnCompile := true,
      libraryDependencies ++= Vector(
            AkkaHttpTestkit,
            AkkaHttpSprayJsonTest,
            TestcontainersKafka % Test,
            ScalaTest,
            Junit
          )
    )
    .settings(
      javacOptions += "-Xlint:deprecation",
      inConfig(Test)(sbtprotoc.ProtocPlugin.protobufConfigSettings),
      PB.targets in Compile := Seq(
            scalapb.gen() -> (sourceManaged in Compile).value / "sproto"
          ),
      PB.protoSources in Compile := Seq(baseDirectory.value / "src/test/protobuf"),
      (sourceGenerators in Test) += (avroScalaGenerateSpecific in Test).taskValue
    )

lazy val spark =
  cloudflowModule("cloudflow-spark")
    .enablePlugins(GenJavadocPlugin, ScalafmtPlugin)
    .dependsOn(streamlets)
    .settings(
      scalafmtOnCompile := true,
      // Prevent incompatible version of jackson-databind
      libraryDependencies ++= Seq(
            AkkaActor,
            AkkaDiscovery,
            AkkaProtobuf,
            AkkaStream,
            Ficus,
            Log4jOverSlf4j,
            Spark,
            SparkMllib,
            SparkSql,
            SparkSqlKafka,
            SparkStreaming,
            SparkProto,
            LogbackClassic,
            LogbackCore,
            ScalaTest
          ),
      libraryDependencies ~= { _.map(_.exclude("org.slf4j", "slf4j-log4j12")) },
      dependencyOverrides += "com.fasterxml.jackson.core"   % "jackson-core"              % "2.11.2",
      dependencyOverrides += "com.fasterxml.jackson.core"   % "jackson-databind"          % "2.11.2",
      dependencyOverrides += "com.fasterxml.jackson.module" % "jackson-module-scala_2.12" % "2.11.2"
    )
    .settings(
      (sourceGenerators in Test) += (avroScalaGenerateSpecific in Test).taskValue
    )

lazy val sparkTestkit =
  cloudflowModule("cloudflow-spark-testkit")
    .enablePlugins(GenJavadocPlugin, ScalafmtPlugin)
    .dependsOn(spark)
    .settings(
      scalafmtOnCompile := true,
      // Prevent incompatible version of jackson-databind
      libraryDependencies ++= Vector(
            ScalaTestUnscoped,
            Junit
          ),
      libraryDependencies ~= { _.map(_.exclude("org.slf4j", "slf4j-log4j12")) },
      dependencyOverrides += "com.fasterxml.jackson.core"   % "jackson-core"              % "2.11.2",
      dependencyOverrides += "com.fasterxml.jackson.core"   % "jackson-databind"          % "2.11.2",
      dependencyOverrides += "com.fasterxml.jackson.module" % "jackson-module-scala_2.12" % "2.11.2"
    )

lazy val sparkTests =
  cloudflowModule("cloudflow-spark-tests")
    .enablePlugins(ScalafmtPlugin)
    .dependsOn(sparkTestkit)
    .settings(
      scalafmtOnCompile := true,
      // Prevent incompatible version of jackson-databind
      libraryDependencies ++= Vector(
            ScalaTest,
            Junit
          ),
      libraryDependencies ~= { _.map(_.exclude("org.slf4j", "slf4j-log4j12")) },
      dependencyOverrides += "com.fasterxml.jackson.core"   % "jackson-core"              % "2.11.2",
      dependencyOverrides += "com.fasterxml.jackson.core"   % "jackson-databind"          % "2.11.2",
      dependencyOverrides += "com.fasterxml.jackson.module" % "jackson-module-scala_2.12" % "2.11.2"
    )
    .settings(
      (sourceGenerators in Test) += (avroScalaGenerateSpecific in Test).taskValue
    )
    .settings(
      parallelExecution in Test := false
    )

lazy val flink =
  cloudflowModule("cloudflow-flink")
    .enablePlugins(ScalafmtPlugin)
    .dependsOn(streamlets)
    .settings(
      scalafmtOnCompile := true,
      libraryDependencies ++= Seq(
            Flink,
            FlinkStreaming,
            FlinkKafka,
            FlinkAvro,
            FlinkWeb,
            LogbackClassic,
            LogbackCore,
            ScalaTest
          ),
      libraryDependencies ~= { _.map(_.exclude("org.slf4j", "slf4j-log4j12")) }
    )
    .settings(
      (sourceGenerators in Test) += (avroScalaGenerateSpecific in Test).taskValue
    )

lazy val flinkTestkit =
  cloudflowModule("cloudflow-flink-testkit")
    .enablePlugins(ScalafmtPlugin)
    .dependsOn(flink)
    .settings(
      scalafmtOnCompile := true,
      libraryDependencies ++= Vector(
            ScalaTestUnscoped,
            Junit
          )
    )

lazy val flinkTests =
  cloudflowModule("cloudflow-flink-tests")
    .enablePlugins(JavaFormatterPlugin, ScalafmtPlugin)
    .dependsOn(flinkTestkit)
    .settings(
      scalafmtOnCompile := true,
      libraryDependencies ++= Vector(
            FlinkAvro,
            JodaTime % Test,
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
    .enablePlugins(BuildInfoPlugin, ScalafmtPlugin)
    .settings(
      scalafmtOnCompile := false,
      Compile / scalafmtCheck := true,
      libraryDependencies ++= Vector(
            Avro,
            Config,
            SprayJson,
            LogbackClassic % Test,
            LogbackCore    % Test,
            Avro4sTest,
            ScalaTest,
            ScalaPbRuntime,
            "org.apache.kafka" % "kafka-clients" % Version.KafkaClients % Test
          ),
      publishArtifact in Test := true
    )
    .settings(
      Compile / unmanagedSourceDirectories += (ThisProject / baseDirectory).value / ".." / ".." / "tools" / "cloudflow-blueprint" / "src" / "main" / "scala",
      crossScalaVersions := Vector(Version.Scala212, Version.Scala213),
      buildInfoKeys := Seq[BuildInfoKey](name, version),
      buildInfoPackage := "cloudflow.blueprint"
    )

lazy val runnerConfig =
  cloudflowModule("cloudflow-runner-config")
    .enablePlugins(BuildInfoPlugin, ScalafmtPlugin)
    .settings(
      scalafmtOnCompile := false,
      Compile / scalafmtCheck := true,
      libraryDependencies ++= Vector(JacksonScalaModule)
    )
    .settings(
      Compile / unmanagedSourceDirectories += (ThisProject / baseDirectory).value / ".." / ".." / "tools" / "cloudflow-runner-config" / "src" / "main" / "scala",
      crossScalaVersions := Vector(Version.Scala212, Version.Scala213)
    )

lazy val runner =
  cloudflowModule("cloudflow-runner")
    .enablePlugins(BuildInfoPlugin, ScalafmtPlugin)
    .dependsOn(
      streamlets,
      blueprint
    )
    .settings(
      crossScalaVersions := Vector(Version.Scala212, Version.Scala213),
      scalafmtOnCompile := true,
      libraryDependencies += Ficus
    )
    .settings(
      artifactName in (Compile, packageBin) := { (sv: ScalaVersion, module: ModuleID, artifact: Artifact) =>
        "runner" + "." + artifact.extension
      }
    )
    .settings(
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
            }
          ),
      buildInfoPackage := "cloudflow.runner"
    )

lazy val localRunner =
  cloudflowModule("cloudflow-localrunner")
    .enablePlugins(BuildInfoPlugin, ScalafmtPlugin)
    .dependsOn(streamlets, blueprint, runnerConfig)
    .settings(
      crossScalaVersions := Vector(Version.Scala212, Version.Scala213),
      scalafmtOnCompile := true
    )

def cloudflowModule(moduleID: String): Project =
  Project(id = moduleID, base = file(moduleID))
    .settings(
      name := moduleID
    )
    .withId(moduleID)
    .settings(commonSettings)
    .enablePlugins(AutomateHeaderPlugin)

lazy val commonSettings = Seq(
  organization := "com.lightbend.cloudflow",
  headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2021", "Lightbend Inc. <https://www.lightbend.com>")),
  scalaVersion := Version.Scala212,
  autoAPIMappings := true,
  useGpgAgent := false,
  releaseProcess := Seq[ReleaseStep](
        // TODO: re-introduce this command
        // checkSnapshotDependencies,
        runClean,
        releaseStepCommand("+test"),
        releaseStepCommandAndRemaining("+publishSigned"),
        releaseStepCommand("sonatypeBundleRelease"),
        pushChanges
      ),
  unidocGenjavadocVersion := "0.16",
  scalacOptions ++= Seq(
        "-encoding",
        "UTF-8",
        "-target:jvm-1.8",
        "-Xlog-reflective-calls",
        "-Xlint",
        "-Ywarn-unused",
        "-deprecation",
        "-feature",
        "-language:_",
        "-unchecked"
      ),
  Compile / doc / scalacOptions := (Compile / doc / scalacOptions).value ++ Seq(
            "-doc-title",
            "Cloudflow",
            "-doc-version",
            version.value,
            "-sourcepath",
            (baseDirectory in ThisBuild).value.toString,
            "-skip-packages",
            "akka.pattern:scala", // for some reason Scaladoc creates this
            "-doc-source-url", {
              val branch = if (isSnapshot.value) "master" else s"v${version.value}"
              s"https://github.com/lightbend/cloudflow/tree/${branch}€{FILE_PATH_EXT}#L€{FILE_LINE}"
            },
            "-doc-canonical-base-url",
            "https://cloudflow.io/docs/current/api/scaladoc/"
          ),
  resolvers += "Akka Snapshots".at("https://repo.akka.io/snapshots/"),
  scalacOptions in (Compile, console) := (scalacOptions in (Global)).value.filter(_ == "-Ywarn-unused-import"),
  scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value,
  publishTo := sonatypePublishToBundle.value
)

releaseIgnoreUntrackedFiles := true
// https://github.com/dwijnand/sbt-dynver#portable-version-strings
dynverSeparator in ThisBuild := "-"

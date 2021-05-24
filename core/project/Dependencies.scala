import sbt.Keys._
import sbt._

object Dependencies {

  val Scala212 = "2.12.12" // has to be the very same as sbt
  val Scala213 = "2.13.3" // Scala 2.13.4 breaks scopt when using "--help"

  object Versions {
    val akka = "2.6.13"
    val akkaHttp = "10.2.4"
    val akkaGrpc = "1.0.2"
    val alpakkaKafka = "2.0.5"
    val akkaMgmt = "1.0.8"
    val flink = "1.10.3"
    val spark = "2.4.5"
    val fabric8 = "5.0.0"
    val jackson = "2.11.4" // same major.minor as used in fabric8
    val slf4j = "1.7.30"
    val scalaTest = "3.2.3"
    val maven = "3.8.1"
  }

  object Compile {
    val fabric8KubernetesClient = "io.fabric8" % "kubernetes-client" % Versions.fabric8

    val typesafeConfig = "com.typesafe" % "config" % "1.4.1"
    val pureConfig = "com.github.pureconfig" %% "pureconfig" % "0.14.0"
    val scopt = "com.github.scopt" %% "scopt" % "4.0.0-RC2"
    val airframeLog = "org.wvlet.airframe" %% "airframe-log" % "20.10.0"
    val asciiTable = "de.vandermeer" % "asciitable" % "0.3.2"

    val logback = "ch.qos.logback" % "logback-classic" % "1.2.3"
    val log4jOverSlf4j = "org.slf4j" % "log4j-over-slf4j" % "1.7.30"

    val scalatest = "org.scalatest" %% "scalatest" % Versions.scalaTest

    // These two dependencies are required to be present at runtime by fabric8, specifically its pod file read methods.
    // Reference:
    // https://github.com/fabric8io/kubernetes-client/blob/0c4513ff30ac9229426f1481a46fde2eb54933d9/kubernetes-client/src/main/java/io/fabric8/kubernetes/client/dsl/internal/core/v1/PodOperationsImpl.java#L451
    val commonsCodec = "commons-codec" % "commons-codec" % "1.15"
    val commonsCompress = "org.apache.commons" % "commons-compress" % "1.20"

    val bouncyCastleCore = "org.bouncycastle" % "bcpkix-jdk15on" % "1.68"
    val bouncyCastleExt = "org.bouncycastle" % "bcprov-ext-jdk15on" % "1.68"

    val akkaActor = "com.typesafe.akka" %% "akka-actor" % Versions.akka
    val akkaTestkit = "com.typesafe.akka" %% "akka-testkit" % Versions.akka
    val akkaStream = "com.typesafe.akka" %% "akka-stream" % Versions.akka
    val akkaStreamTestkit = "com.typesafe.akka" %% "akka-stream-testkit" % Versions.akka
    val akkaSlf4j = "com.typesafe.akka" %% "akka-slf4j" % Versions.akka
    val akkaProtobuf = "com.typesafe.akka" %% "akka-protobuf" % Versions.akka
    val akkaDiscovery = "com.typesafe.akka" %% "akka-discovery" % Versions.akka
    val akkaShardingTyped = "com.typesafe.akka" %% "akka-cluster-sharding-typed" % Versions.akka
    val akkaCluster = "com.typesafe.akka" %% "akka-cluster" % Versions.akka

    val akkaHttp = "com.typesafe.akka" %% "akka-http" % Versions.akkaHttp
    val akkaHttpSprayJson = "com.typesafe.akka" %% "akka-http-spray-json" % Versions.akkaHttp
    val akkaHttpJackson = "com.typesafe.akka" %% "akka-http-jackson" % Versions.akkaHttp
    val akkaHttp2Support = "com.typesafe.akka" %% "akka-http2-support" % Versions.akkaHttp

    val akkaStreamKafka = ("com.typesafe.akka" %% "akka-stream-kafka" % Versions.alpakkaKafka)
      .exclude("com.fasterxml.jackson.core", "jackson-databind")
      .exclude("com.fasterxml.jackson.module", "jackson-module-scala")
    val akkaStreamKafaSharding = "com.typesafe.akka" %% "akka-stream-kafka-cluster-sharding" % Versions.alpakkaKafka
    val akkaStreamKafkaTestkit = ("com.typesafe.akka" %% "akka-stream-kafka-testkit" % Versions.alpakkaKafka)
      .exclude("com.typesafe.akka", "akka-stream-testkit")

    val akkaManagement = "com.lightbend.akka.management" %% "akka-management" % Versions.akkaMgmt
    val akkaClusterBootstrap =
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % Versions.akkaMgmt
    val akkaDiscoveryK8 = "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % Versions.akkaMgmt

    val akkaGrpcRuntime = "com.lightbend.akka.grpc" %% "akka-grpc-runtime" % Versions.akkaGrpc

    val akkaStreamContrib = "com.typesafe.akka" %% "akka-stream-contrib" % "0.10"

    val flink = "org.apache.flink" %% "flink-scala" % Versions.flink
    val flinkStreaming = "org.apache.flink" %% "flink-streaming-scala" % Versions.flink
    val flinkAvro = "org.apache.flink" % "flink-avro" % Versions.flink
    val flinkKafka = "org.apache.flink" %% "flink-connector-kafka" % Versions.flink
    val flinkWeb = "org.apache.flink" %% "flink-runtime-web" % Versions.flink

    val spark = "org.apache.spark" %% "spark-core" % Versions.spark
    val sparkMllib = "org.apache.spark" %% "spark-mllib" % Versions.spark
    val sparkSql = "org.apache.spark" %% "spark-sql" % Versions.spark
    val sparkSqlKafka = "org.apache.spark" %% "spark-sql-kafka-0-10" % Versions.spark
    val sparkStreaming = "org.apache.spark" %% "spark-streaming" % Versions.spark
    val sparkProto = "com.thesamet.scalapb" %% "sparksql-scalapb" % "0.9.0"

    val jacksonCore = "com.fasterxml.jackson.core" % "jackson-core" % Versions.jackson
    val jacksonDatabind = "com.fasterxml.jackson.core" % "jackson-databind" % Versions.jackson
    val jacksonScala = "com.fasterxml.jackson.module" %% "jackson-module-scala" % Versions.jackson

    val sprayJson = "io.spray" %% "spray-json" % "1.3.5"
    val avro = "org.apache.avro" % "avro" % "1.8.2"
    val scalaPbRuntime = "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion

    val bijection = "com.twitter" %% "bijection-avro" % "0.9.7"

    val ficus = "com.iheart" %% "ficus" % "1.4.7"

    val kubeActions = "com.lightbend.akka" %% "kube-actions" % "0.1.1"
    val kafkaClient = "org.apache.kafka" % "kafka-clients" % "2.5.1"

    val classgraph = "io.github.classgraph" % "classgraph" % "4.8.104"

    val scalaPbCompilerPlugin = "com.thesamet.scalapb" %% "compilerplugin" % scalapb.compiler.Version.scalapbVersion
    val testcontainersKafka = "org.testcontainers" % "kafka" % "1.15.2"
    val asciigraphs = "com.github.mutcianm" %% "ascii-graphs" % "0.0.6"

    val mavenPluginApi = "org.apache.maven" % "maven-plugin-api" % Versions.maven
    val mavenCore = "org.apache.maven" % "maven-core" % Versions.maven
    val mavenEmbedder = "org.apache.maven" % "maven-embedder" % Versions.maven
    val mavenPluginAnnotations = "org.apache.maven.plugin-tools" % "maven-plugin-annotations" % "3.6.1"
    val mavenProject = "org.apache.maven" % "maven-project" % "2.2.1"
    val mojoExecutor = "org.twdata.maven" % "mojo-executor" % "2.3.1"
  }

  object TestDeps {

    val fabric8KubernetesServerMock = "io.fabric8" % "kubernetes-server-mock" % Versions.fabric8 % Test

    val avro4s = "com.sksamuel.avro4s" %% "avro4s-core" % "3.0.0" % Test

    val scalatestJunit = "org.scalatestplus" %% "junit-4-13" % s"${Versions.scalaTest}.0" % Test

    val akkaHttpTestkit = "com.typesafe.akka" %% "akka-http-testkit" % Versions.akkaHttp % Test

    val jodaTime = "joda-time" % "joda-time" % "2.10.6"

  }

  val cloudflowConfig =
    libraryDependencies ++= Seq(
        Compile.fabric8KubernetesClient,
        Compile.jacksonScala,
        Compile.typesafeConfig,
        Compile.pureConfig,
        Compile.scalatest % Test)

  val cloudflowCli =
    libraryDependencies ++= Seq(
        Compile.logback,
        Compile.scopt,
        Compile.airframeLog,
        Compile.asciiTable,
        Compile.bouncyCastleCore,
        Compile.bouncyCastleExt,
        TestDeps.fabric8KubernetesServerMock,
        Compile.scalatest % Test)

  val cloudflowCrd =
    libraryDependencies ++= Seq(Compile.fabric8KubernetesClient, Compile.jacksonScala, Compile.scalatest % Test)

  val cloudflowIt =
    libraryDependencies ++= Seq(Compile.commonsCodec % Test, Compile.commonsCompress % Test, Compile.scalatest % Test)

  val cloudflowNewItLibrary =
    libraryDependencies ++= Seq(Compile.commonsCodec, Compile.commonsCompress, Compile.scalatest)

  val cloudflowBlueprint =
    libraryDependencies ++= Seq(
        Compile.typesafeConfig,
        Compile.sprayJson,
        // TODO: check if Avro and ScalaPB can stay in a separate module
        Compile.avro,
        Compile.scalaPbRuntime,
        Compile.logback % Test,
        Compile.scalatest % Test,
        Compile.kafkaClient % Test,
        TestDeps.avro4s)

  val cloudflowOperator =
    libraryDependencies ++= Seq(
        Compile.akkaActor,
        Compile.akkaStream,
        Compile.akkaHttp,
        Compile.akkaSlf4j,
        Compile.logback,
        Compile.kubeActions,
        Compile.kafkaClient,
        Compile.scalatest % Test,
        TestDeps.avro4s)

  val cloudflowExtractor =
    libraryDependencies ++= Seq(Compile.typesafeConfig, Compile.classgraph, Compile.scalatest % Test)

  val cloudflowSbtPlugin =
    libraryDependencies ++= Seq(
        Compile.scalaPbCompilerPlugin,
        Compile.asciigraphs,
        Compile.testcontainersKafka,
        Compile.kafkaClient,
        Compile.scalatest % Test)

  val cloudflowRunnerConfig =
    libraryDependencies ++= Seq(Compile.jacksonScala, Compile.typesafeConfig % Test, Compile.scalatest % Test)

  val cloudflowStreamlet =
    libraryDependencies ++= Seq(
        Compile.sprayJson,
        Compile.bijection,
        Compile.avro,
        Compile.scalaPbRuntime,
        Compile.typesafeConfig,
        Compile.ficus,
        Compile.scalatest % Test)

  val cloudflowAkkastream =
    libraryDependencies ++= Seq(
        Compile.akkaActor,
        Compile.akkaStream,
        Compile.akkaSlf4j,
        Compile.akkaDiscovery,
        Compile.akkaHttp,
        Compile.akkaHttpSprayJson,
        Compile.akkaStreamKafka,
        Compile.akkaStreamKafaSharding,
        Compile.akkaShardingTyped,
        Compile.akkaCluster,
        Compile.akkaManagement,
        Compile.akkaClusterBootstrap,
        Compile.akkaDiscoveryK8,
        Compile.logback,
        Compile.jacksonScala,
        Compile.sprayJson,
        Compile.ficus)

  val cloudflowAkkastreamTestkit =
    libraryDependencies ++= Seq(
        Compile.akkaSlf4j,
        Compile.akkaStream,
        Compile.akkaStreamContrib,
        Compile.ficus,
        Compile.akkaStreamKafkaTestkit,
        Compile.akkaStreamTestkit,
        Compile.akkaTestkit,
        Compile.scalatest,
        TestDeps.scalatestJunit)

  val cloudflowAkkaUtil =
    libraryDependencies ++= Vector(
        Compile.akkaHttp,
        Compile.akkaHttpJackson,
        Compile.akkaHttp2Support,
        Compile.akkaGrpcRuntime,
        Compile.akkaStreamContrib,
        Compile.akkaStreamTestkit % Test,
        TestDeps.akkaHttpTestkit,
        Compile.scalatest % Test,
        TestDeps.scalatestJunit)

  val cloudflowAkkastreamTests =
    libraryDependencies ++= Vector(
        TestDeps.akkaHttpTestkit,
        Compile.akkaHttpSprayJson % Test,
        Compile.testcontainersKafka % Test,
        Compile.testcontainersKafka % Test,
        Compile.scalatest % Test,
        TestDeps.scalatestJunit)

  val cloudflowFlinkStreamlet = Seq(
    libraryDependencies ++= Seq(
        Compile.flink,
        Compile.flinkStreaming,
        Compile.flinkKafka,
        Compile.flinkAvro,
        Compile.flinkWeb,
        Compile.logback,
        Compile.scalatest % Test))

  val cloudflowFlinkTests =
    libraryDependencies ++= Seq(Compile.scalatest % Test, TestDeps.scalatestJunit, TestDeps.jodaTime)

  val cloudflowSparkStreamlet = Seq(
    libraryDependencies ++= Seq(
        Compile.akkaActor,
        Compile.akkaStream,
        Compile.akkaProtobuf,
        Compile.akkaDiscovery,
        Compile.log4jOverSlf4j,
        Compile.spark,
        Compile.sparkMllib,
        Compile.sparkSql,
        Compile.sparkSqlKafka,
        Compile.sparkStreaming,
        Compile.sparkProto,
        Compile.logback,
        Compile.scalatest % Test),
    libraryDependencies ~= { _.map(_.exclude("org.slf4j", "slf4j-log4j12")) },
    dependencyOverrides ++= Seq(Compile.jacksonCore, Compile.jacksonDatabind, Compile.jacksonScala))

  val cloudflowSparkTestkit = Seq(
    libraryDependencies ++= Seq(Compile.scalatest, TestDeps.scalatestJunit, TestDeps.jodaTime),
    dependencyOverrides ++= Seq(Compile.jacksonCore, Compile.jacksonDatabind, Compile.jacksonScala))

  val cloudflowSparkTests =
    dependencyOverrides ++= Seq(Compile.jacksonCore, Compile.jacksonDatabind, Compile.jacksonScala)

  val cloudflowCrGenerator =
    libraryDependencies += Compile.scopt

  val cloudflowMavenPlugin =
    libraryDependencies ++= Seq(
        Compile.mavenCore,
        Compile.mavenEmbedder,
        Compile.mavenProject,
        Compile.mavenPluginApi,
        Compile.mojoExecutor,
        Compile.mavenPluginAnnotations)

  val cloudflowBuildSupport =
    libraryDependencies ++= Seq(
        Compile.typesafeConfig,
        Compile.asciigraphs,
        Compile.testcontainersKafka,
        Compile.kafkaClient)
}

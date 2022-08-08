import sbt.Keys._
import sbt._

object Dependencies {

  val Scala212 = "2.12.15" // has to be the very same as sbt
  val Scala213 = "2.13.8"

  object Versions {
    val akka = "2.6.19"
    val akkaHttp = "10.2.9"
    val akkaGrpc = "2.1.4"
    val alpakkaKafka = "2.1.1"
    val akkaMgmt = "1.1.3"
    val spark = "2.4.5"
    val fabric8 = "5.0.3"
    val jackson = "2.13.3"
    //TODO remove jacksonDatabind when jackson 2.13.3 plus excludes in avro and jacksonScala
    val jacksonDatabind = "2.13.3"
    val slf4j = "1.7.30"
    val scalaTest = "3.2.13"
    val maven = "3.8.5"
  }

  object Compile {
    val fabric8KubernetesClient = "io.fabric8" % "kubernetes-client" % Versions.fabric8

    val typesafeConfig = "com.typesafe" % "config" % "1.4.2"
    val pureConfig = "com.github.pureconfig" %% "pureconfig" % "0.17.1"
    val scopt =
      "com.github.scopt" %% "scopt" % "4.1.0" // FIXME generating docs for CLI fails with concurrent modification with 2.13 and this version.
    val airframeLog = "org.wvlet.airframe" %% "airframe-log" % "22.5.0"
    val asciiTable = "de.vandermeer" % "asciitable" % "0.3.2"

    val logback = "ch.qos.logback" % "logback-classic" % "1.2.11"

    val scalatest = "org.scalatest" %% "scalatest" % Versions.scalaTest
    val scalatestMustMatchers = "org.scalatest" %% "scalatest-mustmatchers" % Versions.scalaTest
    // These two dependencies are required to be present at runtime by fabric8, specifically its pod file read methods.
    // Reference:
    // https://github.com/fabric8io/kubernetes-client/blob/0c4513ff30ac9229426f1481a46fde2eb54933d9/kubernetes-client/src/main/java/io/fabric8/kubernetes/client/dsl/internal/core/v1/PodOperationsImpl.java#L451
    val commonsCodec = "commons-codec" % "commons-codec" % "1.15"
    val commonsCompress = "org.apache.commons" % "commons-compress" % "1.21"

    val bouncyCastleCore = "org.bouncycastle" % "bcpkix-jdk15on" % "1.70"
    val bouncyCastleExt = "org.bouncycastle" % "bcprov-ext-jdk15on" % "1.70"

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

    val akkaStreamContrib = "com.typesafe.akka" %% "akka-stream-contrib" % "0.11"
    val avro = ("org.apache.avro" % "avro" % "1.11.1")
      .exclude("com.fasterxml.jackson.core", "jackson-databind")

    val jacksonCore = "com.fasterxml.jackson.core" % "jackson-core" % Versions.jackson
    val jacksonDatabind = "com.fasterxml.jackson.core" % "jackson-databind" % Versions.jacksonDatabind
    val jacksonScala = ("com.fasterxml.jackson.module" %% "jackson-module-scala" % Versions.jackson)
      .exclude("com.fasterxml.jackson.core", "jackson-databind")

    val slf4jApi = "org.slf4j" % "slf4j-api" % "1.7.36"
    val sprayJson = "io.spray" %% "spray-json" % "1.3.6"
    val scalaPbRuntime = "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion

    val bijection = "com.twitter" %% "bijection-avro" % "0.9.7"

    val ficus = "com.iheart" %% "ficus" % "1.5.2"

    val kubeActions = "com.lightbend.akka" %% "kube-actions" % "0.1.1"

    val kafkaClient = "org.apache.kafka" % "kafka-clients" % "3.2.1"

    val classgraph = "io.github.classgraph" % "classgraph" % "4.8.149"

    val scalaPbCompilerPlugin = "com.thesamet.scalapb" %% "compilerplugin" % scalapb.compiler.Version.scalapbVersion
    val testcontainersKafka = "org.testcontainers" % "kafka" % "1.17.3"
    val asciigraphs = "com.github.mutcianm" %% "ascii-graphs" % "0.0.6"

    val mavenPluginApi = "org.apache.maven" % "maven-plugin-api" % Versions.maven
    val mavenCore = "org.apache.maven" % "maven-core" % Versions.maven
    val mavenEmbedder = "org.apache.maven" % "maven-embedder" % Versions.maven
    val mavenPluginAnnotations = "org.apache.maven.plugin-tools" % "maven-plugin-annotations" % "3.6.4"
    val mavenProject = "org.apache.maven" % "maven-project" % "2.2.1"
    val mojoExecutor = "org.twdata.maven" % "mojo-executor" % "2.4.0"
    val junit = "junit" % "junit" % "4.13.2"
  }

  object TestDeps {

    val fabric8KubernetesServerMock = "io.fabric8" % "kubernetes-server-mock" % Versions.fabric8 % Test

    val akkaHttpJackson = "com.typesafe.akka" %% "akka-http-jackson" % Versions.akkaHttp % Test

    val akkaHttpTestkit = "com.typesafe.akka" %% "akka-http-testkit" % Versions.akkaHttp % Test

    val avro4s = "com.sksamuel.avro4s" %% "avro4s-core" % "4.1.0" % Test

    val scalatestJunit = "org.scalatestplus" %% "junit-4-13" % s"${Versions.scalaTest}.0" % Test

    val jodaTime = "joda-time" % "joda-time" % "2.10.6"
  }

  val cloudflowAvro =
    libraryDependencies ++= Seq(Compile.avro, Compile.bijection)

  val cloudflowConfig =
    libraryDependencies ++= Seq(
        Compile.fabric8KubernetesClient,
        Compile.jacksonScala,
        Compile.jacksonDatabind,
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
    libraryDependencies ++= Seq(
        Compile.fabric8KubernetesClient,
        Compile.jacksonScala,
        Compile.jacksonDatabind,
        Compile.scalatest % Test)

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
        Compile.jacksonDatabind,
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
        Compile.jacksonScala,
        Compile.jacksonDatabind,
        Compile.kubeActions,
        Compile.kafkaClient,
        Compile.scalatest % Test,
        TestDeps.avro4s)

  val cloudflowExtractor =
    libraryDependencies ++= Seq(Compile.typesafeConfig, Compile.classgraph, Compile.scalatest % Test)

  val cloudflowProto =
    libraryDependencies ++= Seq(Compile.scalaPbRuntime)

  val cloudflowSbtPlugin =
    libraryDependencies ++= Seq(
        Compile.scalaPbCompilerPlugin,
        Compile.asciigraphs,
        Compile.testcontainersKafka,
        Compile.kafkaClient,
        Compile.scalatest % Test)

  val cloudflowRunnerConfig =
    libraryDependencies ++= Seq(
        Compile.jacksonScala,
        Compile.jacksonDatabind,
        Compile.typesafeConfig % Test,
        Compile.scalatest % Test)

  val cloudflowStreamlet =
    libraryDependencies ++= Seq(
        Compile.sprayJson,
        Compile.typesafeConfig,
        Compile.slf4jApi,
        Compile.ficus,
        Compile.scalatest % Test)

  val cloudflowAkka =
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
        Compile.jacksonDatabind,
        Compile.sprayJson,
        Compile.ficus)

  val cloudflowAkkaTestkit =
    libraryDependencies ++= Seq(
        Compile.akkaSlf4j,
        Compile.akkaStream,
        Compile.akkaStreamContrib,
        Compile.ficus,
        Compile.akkaStreamKafkaTestkit,
        Compile.akkaStreamTestkit,
        Compile.akkaTestkit,
        Compile.scalatest,
        Compile.scalatestMustMatchers % "test",
        Compile.scalatest % Test,
        TestDeps.scalatestJunit)

  val cloudflowAkkaUtil =
    libraryDependencies ++= Vector(
        Compile.akkaHttp,
        Compile.akkaHttp2Support,
        Compile.akkaGrpcRuntime,
        Compile.akkaStreamContrib,
        Compile.akkaStreamTestkit % Test,
        Compile.scalatest % Test,
        TestDeps.akkaHttpTestkit,
        TestDeps.akkaHttpJackson,
        TestDeps.scalatestJunit)

  val cloudflowAkkaTests =
    libraryDependencies ++= Vector(
        TestDeps.akkaHttpTestkit,
        Compile.akkaHttpSprayJson % Test,
        Compile.testcontainersKafka % Test,
        Compile.testcontainersKafka % Test,
        Compile.scalatest % Test,
        TestDeps.scalatestJunit)

  val cloudflowCrGenerator =
    libraryDependencies += Compile.scopt

  val cloudflowMavenPlugin =
    libraryDependencies ++= Seq(
        Compile.junit,
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

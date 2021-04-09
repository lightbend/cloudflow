import sbt.Keys._
import sbt._

object Dependencies {

  val Scala212 = "2.12.12" // has to be the very same as sbt
  val Scala213 = "2.13.3" // Scala 2.13.4 breaks scopt when using "--help"

  object Versions {
    val akka = "2.6.13"
    val akkaHttp = "10.2.4"
    val akkaGrpc = "1.0.2"
    val fabric8 = "5.0.0"
    val jackson = "2.11.4" // same major.minor as used in fabric8
    val slf4j = "1.7.30"
    val scalaTest = "3.2.3"
  }

  object Compile {
    val fabric8KubernetesClient = "io.fabric8" % "kubernetes-client" % Versions.fabric8
    val jacksonScala = "com.fasterxml.jackson.module" %% "jackson-module-scala" % Versions.jackson

    val typesafeConfig = "com.typesafe" % "config" % "1.4.1"
    val pureConfig = "com.github.pureconfig" %% "pureconfig" % "0.14.0"
    val pureConfigMagnolia = "com.github.pureconfig" %% "pureconfig-magnolia" % "0.14.0"
    val scopt = "com.github.scopt" %% "scopt" % "4.0.0-RC2"
    val airframeLog = "org.wvlet.airframe" %% "airframe-log" % "20.10.0"
    val asciiTable = "de.vandermeer" % "asciitable" % "0.3.2"

    val logback = "ch.qos.logback" % "logback-classic" % "1.2.3"

    val scalatest = "org.scalatest" %% "scalatest" % Versions.scalaTest
    // These two dependencies are required to be present at runtime by fabric8, specifically its pod file read methods.
    // Reference:
    // https://github.com/fabric8io/kubernetes-client/blob/0c4513ff30ac9229426f1481a46fde2eb54933d9/kubernetes-client/src/main/java/io/fabric8/kubernetes/client/dsl/internal/core/v1/PodOperationsImpl.java#L451
    val commonsCodec = "commons-codec" % "commons-codec" % "1.15"
    val commonsCompress = "org.apache.commons" % "commons-compress" % "1.20"

    val bouncyCastleCore = "org.bouncycastle" % "bcpkix-jdk15on" % "1.68"
    val bouncyCastleExt = "org.bouncycastle" % "bcprov-ext-jdk15on" % "1.68"

    val akkaActor = "com.typesafe.akka" %% "akka-actor" % Versions.akka
    val akkaStream = "com.typesafe.akka" %% "akka-stream" % Versions.akka
    val akkaSlf4j = "com.typesafe.akka" %% "akka-slf4j" % Versions.akka
    val akkaHttp = "com.typesafe.akka" %% "akka-http" % Versions.akkaHttp

    val sprayJson = "io.spray" %% "spray-json" % "1.3.5"
    val avro = "org.apache.avro" % "avro" % "1.8.2"
    val scalaPbRuntime = "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion

    val kubeActions = "com.lightbend.akka" %% "kube-actions" % "0.1.1"
    val kafkaClient = "org.apache.kafka" % "kafka-clients" % "2.5.1"

    val classgraph = "io.github.classgraph" % "classgraph" % "4.8.104"

    val scalaPbCompilerPlugin = "com.thesamet.scalapb" %% "compilerplugin" % scalapb.compiler.Version.scalapbVersion
    val testcontainersKafka = "org.testcontainers" % "kafka" % "1.15.2"
    val asciigraphs = "com.github.mutcianm" %% "ascii-graphs" % "0.0.6"
  }

  object TestDeps {

    val fabric8KubernetesServerMock = "io.fabric8" % "kubernetes-server-mock" % Versions.fabric8 % Test

    val avro4s = "com.sksamuel.avro4s" %% "avro4s-core" % "3.0.0" % Test

  }

  val cloudflowConfig =
    libraryDependencies ++= Seq(
        Compile.fabric8KubernetesClient,
        Compile.jacksonScala,
        Compile.typesafeConfig,
        Compile.pureConfig,
        Compile.pureConfigMagnolia,
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

}

import sbt.Keys._
import sbt._

object Dependencies {

  val Scala213 = "2.13.3" // Scala 2.13.4 breaks scopt when using "--help"

  object Versions {
    val fabric8 = "5.0.0"
    val jackson = "2.11.4" // same major.minor as used in fabric8
    val slf4j = "1.7.30"
    val scalaTest = "3.2.3"
  }

  object Compile {
    val fabric8KubernetesClient = "io.fabric8" % "kubernetes-client" % Versions.fabric8
    val jacksonScala = "com.fasterxml.jackson.module" %% "jackson-module-scala" % Versions.jackson

    val typesafeConfig = "com.typesafe" % "config" % "1.4.0"
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
  }

  object TestDeps {

    val fabric8KubernetesServerMock = "io.fabric8" % "kubernetes-server-mock" % Versions.fabric8 % Test

  }

  val cloudflowCli =
    libraryDependencies ++= Seq(
        Compile.fabric8KubernetesClient,
        Compile.jacksonScala,
        Compile.logback,
        Compile.scopt,
        Compile.typesafeConfig,
        Compile.pureConfig,
        Compile.pureConfigMagnolia,
        Compile.airframeLog,
        Compile.asciiTable,
        TestDeps.fabric8KubernetesServerMock,
        Compile.scalatest % Test)

  val cloudflowCrd =
    libraryDependencies ++= Seq(Compile.fabric8KubernetesClient, Compile.jacksonScala, Compile.scalatest % Test)

}

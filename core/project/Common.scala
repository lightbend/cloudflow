import org.scalafmt.sbt.ScalafmtPlugin.autoImport._
import sbt.Keys._
import sbt._
import sbt.plugins.JvmPlugin
import xerial.sbt.Sonatype.SonatypeKeys.sonatypePublishToBundle
import com.jsuereth.sbtpgp.PgpKeys.useGpgAgent
import com.lightbend.sbt.JavaFormatterPlugin.autoImport.javafmtOnCompile
import sbtunidoc.GenJavadocPlugin.autoImport.unidocGenjavadocVersion

object Common extends AutoPlugin {

  override def trigger = allRequirements

  override def requires = JvmPlugin

  override def globalSettings =
    Seq(
      organization := "com.lightbend.cloudflow",
      organizationName := "Lightbend Inc.",
      organizationHomepage := Some(url("https://www.lightbend.com/")),
      startYear := Some(2020),
      description := "Cloudflow enables users to quickly develop, orchestrate, and operate distributed streaming applications on Kubernetes.",
      homepage := Some(url("https://cloudflow.io")),
      scmInfo := Some(ScmInfo(url("https://github.com/lightbend/cloudflow"), "git@github.com:lightbend/cloudflow.git")),
      licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
      publishMavenStyle := true,
      developers += Developer(
          "contributors",
          "Contributors",
          "https://cloudflow.zulipchat.com/",
          url("https://github.com/lightbend/cloudflow/graphs/contributors")),
      excludeLintKeys ++= Set(unidocGenjavadocVersion, useGpgAgent, publishMavenStyle, crossSbtVersions, javacOptions))

  override lazy val projectSettings = Seq(
    crossVersion := CrossVersion.binary,
    scalacOptions ++= List("-feature", "-deprecation"),
    publishTo := sonatypePublishToBundle.value,
    useGpgAgent := false,
    scalafmtOnCompile := true,
    // TODO: disabled since there are problems in cross JVMs compilation re-enable me possibly
    javafmtOnCompile := false,
    run / fork := false,
    unidocGenjavadocVersion := "0.18_2.13.10",
    // show full stack traces and test case durations
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
    // -a Show stack traces and exception class name for AssertionErrors.
    // -v Log "test run started" / "test started" / "test run finished" events on log level "info" instead of "debug".
    // -q Suppress stdout for successful tests.
    Test / testOptions += Tests.Argument(TestFrameworks.JUnit, "-a", "-v", "-q"),
    Test / logBuffered := false)

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
    "/cloudflow-streamlets/target/java/cloudflow/streamlets/bytearray/ExternalOutlet.java")

}

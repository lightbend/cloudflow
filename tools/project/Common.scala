import org.scalafmt.sbt.ScalafmtPlugin.autoImport._
import sbt.Keys._
import sbt._
import sbt.plugins.JvmPlugin
import xerial.sbt.Sonatype.SonatypeKeys.sonatypePublishToBundle
import com.jsuereth.sbtpgp.PgpKeys.useGpgAgent

object Common extends AutoPlugin {

  override def trigger = allRequirements

  override def requires = JvmPlugin

  override def globalSettings =
    Seq(
      organization := "com.lightbend.cloudflow",
      organizationName := "Lightbend Inc.",
      organizationHomepage := Some(url("https://www.lightbend.com/")),
      startYear := Some(2020),
      description := "Cloudflow tools")

  override lazy val projectSettings = Seq(
    crossVersion := CrossVersion.full,
    scalacOptions ++= List("-feature", "-deprecation"),
    javacOptions ++= List("-Xlint:unchecked", "-Xlint:deprecation"),
    publishTo := sonatypePublishToBundle.value,
    useGpgAgent := false,
    scalafmtOnCompile := true,
    run / fork := false,
    // show full stack traces and test case durations
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
    // -a Show stack traces and exception class name for AssertionErrors.
    // -v Log "test run started" / "test started" / "test run finished" events on log level "info" instead of "debug".
    // -q Suppress stdout for successful tests.
    Test / testOptions += Tests.Argument(TestFrameworks.JUnit, "-a", "-v", "-q"),
    Test / logBuffered := false)

}

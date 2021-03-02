import org.scalafmt.sbt.ScalafmtPlugin.autoImport._
import sbt.Global
import sbt.Keys._
import sbt._
import sbt.plugins.JvmPlugin
import sbtdynver.DynVerPlugin.autoImport._

object Common extends AutoPlugin {

  override def trigger = allRequirements

  override def requires = JvmPlugin

  override def globalSettings =
    Seq(
      organization := "com.lightbend.cloudflow",
      organizationName := "Lightbend Inc.",
      organizationHomepage := Some(url("https://www.lightbend.com/")),
      startYear := Some(2020),
      description := "Cloudflow kubectl plugin")

  override lazy val projectSettings = Seq(
    crossVersion := CrossVersion.binary,
    scalaVersion := Dependencies.Scala213,
    scalacOptions += "-feature",
    javacOptions ++= List("-Xlint:unchecked", "-Xlint:deprecation"),
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

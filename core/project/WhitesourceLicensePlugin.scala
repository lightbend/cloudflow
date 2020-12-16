// import sbt._

// import sbtwhitesource.WhiteSourcePlugin.autoImport._

// object LicensePlugin extends AutoPlugin {
//   override def requires = plugins.JvmPlugin
//   override def trigger  = allRequirements

//   override def buildSettings: Seq[Setting[_]] =
//     List(
//       whitesourceProduct := "cloudflow",
//       whitesourceAggregateProjectName := "cloudflow-streamlets-1.0-stable",
//       // The project token is just a project UID, not a password.
//       whitesourceAggregateProjectToken := "7a76fc644e2d4ffc8a1b8ca9a9406e78624994604c6f43fc8bdbbda4a188d62a"
//     )
// }

import sbt._
import sbt.Keys._
import sbtwhitesource.WhiteSourcePlugin.autoImport._
import sbtwhitesource._
import scala.sys.process.Process
import scala.util.Try

object Whitesource extends AutoPlugin {
  private lazy val gitCurrentBranch =
    Try(Process("git rev-parse --abbrev-ref HEAD").!!.replaceAll("\\s", "")).recover {
      case e => sys.error(s"Couldn't determine git branch for Whitesource: $e")
    }.toOption

  override def requires = WhiteSourcePlugin

  override def trigger = allRequirements

  override lazy val projectSettings = Seq(
    // do not change the value of whitesourceProduct
    whitesourceProduct := "cloudflow",
    whitesourceAggregateProjectName := {
      "cloudflow-2.0-" + (
        if (isSnapshot.value)
          if (gitCurrentBranch.contains("master")) "master"
          else "adhoc"
        else majorMinor((LocalRootProject / version).value).map(_ + "-stable").getOrElse("adhoc")
      )
    },
    whitesourceForceCheckAllDependencies := true,
    whitesourceFailOnError := true)

  private def majorMinor(version: String): Option[String] = """\d+\.\d+""".r.findFirstIn(version)
}

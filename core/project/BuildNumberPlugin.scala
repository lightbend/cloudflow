import sbt._
import sbt.Keys._

import scala.util.Try

object BuildNumberPlugin extends AutoPlugin {
  final case class BuildNumber(value: String, isGitClean: Boolean) {
    def asVersion: String = if (isGitClean) value else s"${value}-dirty"
  }

  trait Keys {
    val cloudflowBuildNumber = taskKey[BuildNumber]("The current cloudflow build number (i.e. ${numberOfGitCommits}-${gitHeadCommit}).")
  }

  object Keys extends Keys
  object autoImport extends Keys

  import Keys._

  override def requires = plugins.JvmPlugin
  override def projectSettings = Seq(
    cloudflowBuildNumber := generateBuildNumber,
    version := generateBuildNumber.asVersion
  )

  private def generateBuildNumber = {
    import scala.sys.process._

    if (Try("git --version".!!).isSuccess) {
      if (Try("git rev-parse --git-dir".!!).isSuccess) {
        val isClean = "git diff --quiet --ignore-submodules HEAD".! == 0
        val commits = "git rev-list --count HEAD".!!.trim()
        val hash = "git rev-parse --short HEAD".!!.trim()
        val build = s"${commits}-${hash}"

        BuildNumber(build, isClean)
      } else {
        sys.error("The current project is not a valid Git project.")
      }

    } else {
      sys.error("Git is not installed or cannot be found on this machine.")
    }
  }
}


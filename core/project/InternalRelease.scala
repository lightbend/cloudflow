import sbt._

import sbtrelease.ReleasePlugin.autoImport.ReleaseKeys.releaseCommand
import sbtrelease.ReleasePlugin.autoImport.{
  releaseProcess,
  ReleaseStep,
  releaseVersion,
  releaseNextVersion
}
import sbtrelease.{versionFormatError, ReleaseStateTransformations, Version => RVersion}
import sbtrelease.ReleaseStateTransformations._
import scala.util.Try

object InternalReleaseCommand {
  /**
   * Command to publish development builds.
   * Builds and publishs version <version>-<commit_count>-<commit_hash>, and push the tag v<version>-<commit_count>-<commit_hash>.
   */
  val command: Command = Command.make("internalRelease"){ state =>

    // tweak the keys used by sbt-release, for develop build
    val extracted = Project.extract(state)
    val updatedState = extracted.appendWithSession(List(
      releaseVersion := {ver => RVersion(ver).map(_.copy(qualifier = Some(gitQualifier())).string).getOrElse(versionFormatError(ver))},
      releaseNextVersion := {ver => RVersion(ver).map(_.asSnapshot.string).getOrElse(versionFormatError(ver))},
      /* The logical steps which are executed are:
       * - checking that the project is in a valid state
       *   - clean and synced git (checks from tagReleaseWithChecks)
       *   - project compiles and tests passes (checkSnapshotDependencies, inquireVersions, runClean, runTest)
       * - tag the existing commit (tagReleaseWithChecks)
       * - publish using the build number (setReleaseVersion, publishArtifacts, setNextVersion)
       * - push the tag to the remote repo (pushChanges)
       */
      releaseProcess := Seq[ReleaseStep](
        checkSnapshotDependencies,
        inquireVersions,
        runClean,
        runTest,
        setReleaseVersion,
        tagReleaseWithChecks,
        publishArtifacts,
        setNextVersion,
        pushChanges
      )), state)

    releaseCommand.parser(updatedState)
  }

  def gitQualifier(): String = {
    import scala.sys.process._

    if (Try("git --version".!!).isSuccess) {
      if (Try("git rev-parse --git-dir".!!).isSuccess) {
        val commits = "git rev-list --count HEAD".!!.trim()
        val hash = "git rev-parse --short HEAD".!!.trim()
        s"-${commits}-${hash}"
      } else {
        sys.error("The current project is not a valid Git project.")
      }

    } else {
      sys.error("Git is not installed or cannot be found on this machine.")
    }
  }

  // extract the checks we want that are attached to other commands
  lazy val checkUpstream = ReleaseStateTransformations.pushChanges.check
  lazy val initialVcsChecks = ReleaseStateTransformations.commitReleaseVersion.check

  // attach the checks to the tagRelease step
  lazy val tagReleaseWithChecks: ReleaseStep =
    ReleaseStep(
      ReleaseStateTransformations.tagRelease,
      s => checkUpstream(initialVcsChecks(s)))

}

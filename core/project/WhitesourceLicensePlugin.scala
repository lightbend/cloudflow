import sbt._

import sbtwhitesource.WhiteSourcePlugin.autoImport._

object LicensePlugin extends AutoPlugin {
  override def requires = plugins.JvmPlugin
  override def trigger  = allRequirements

  override def buildSettings: Seq[Setting[_]] =
    List(
      whitesourceProduct := "cloudflow",
      whitesourceAggregateProjectName := "cloudflow-streamlets-1.0-stable",
      // The project token is just a project UID, not a password.
      whitesourceAggregateProjectToken := "7a76fc644e2d4ffc8a1b8ca9a9406e78624994604c6f43fc8bdbbda4a188d62a"
    )
}

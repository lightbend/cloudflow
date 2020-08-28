// Your profile name of the sonatype account. The default is the same with the organization value
ThisBuild / sonatypeProfileName := "com.lightbend.cloudflow"

// To sync with Maven central, you need to supply the following information:
ThisBuild / publishMavenStyle := true

// Open-source license of your choice
ThisBuild / licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))

// Where is the source code hosted: GitHub or GitLab?
import xerial.sbt.Sonatype._
ThisBuild / sonatypeProjectHosting := Some(GitHubHosting("lightbend", "cloudflow", "info@lightbend.com"))

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/lightbend/cloudflow"),
    "scm:git@github.com:lightbend/cloudflow.git"
  )
)

ThisBuild / pomExtra := (
  <url>https://github.com/lightbend/cloudflow</url>
)

ThisBuild / organizationName := "Lightbend Inc."
ThisBuild / organizationHomepage := Some(url("https://www.lightbend.com/"))
ThisBuild / homepage := Some(url("https://cloudflow.io"))
ThisBuild / developers += Developer("contributors",
                                    "Contributors",
                                    "https://gitter.im/lightbend/cloudflow",
                                    url("https://github.com/lightbend/cloudflow/graphs/contributors"))
ThisBuild / description := "Cloudflow enables users to quickly develop, orchestrate, and operate distributed streaming applications on Kubernetes."

// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ =>
  false
}
ThisBuild / publishTo := sonatypePublishToBundle.value

ThisBuild / publishConfiguration := publishConfiguration.value.withOverwrite(true)
ThisBuild / publishLocalConfiguration := publishLocalConfiguration.value.withOverwrite(true)

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

ThisBuild / developers := List(
  Developer(id="agemooij", name="Age Mooij", email="age.mooij@gmail.com", url=url("http://lightbend.com")),
  Developer(id="RayRoestenburg", name="Raymond Roestenburg", email="raymond.roestenburg@gmail.com", url=url("http://lightbend.com")),
  Developer(id="olofwalker", name="Robert Walker", email="robert@walker.st", url=url("http://lightbend.com")),
  Developer(id="maasg", name="Gerard Maas", email="gerard.maas@gmail.com", url=url("http://lightbend.com")),
  Developer(id="debasishg", name="Debasish Ghosh", email="dghosh@acm.org", url=url("http://lightbend.com")),
  Developer(id="seglo", name="Sean Glover", email="sean@seanglover.com", url=url("http://lightbend.com")),
  Developer(id="skyluc", name="Luc Bourlier", email="luc.bourlier@typesafe.com", url=url("http://lightbend.com")),
  Developer(id="skonto", name="Stavros Kontopoulos", email="skonto@users.noreply.github.com", url=url("http://lightbend.com")),
  Developer(id="yuchaoran2011", name="Chaoran Yu", email="yuchaoran2011@gmail.com", url=url("http://lightbend.com"))
)

// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

ThisBuild / publishConfiguration := publishConfiguration.value.withOverwrite(true)
ThisBuild / publishLocalConfiguration := publishLocalConfiguration.value.withOverwrite(true)



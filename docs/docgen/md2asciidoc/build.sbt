import sbt._
import sbt.Keys._

lazy val md2asciidoc = (project in file("."))
  .settings(
    version := "0.1",
    libraryDependencies ++= Seq(
      "ca.szc.thirdparty.nl.jworks.markdown_to_asciidoc" % "markdown_to_asciidoc" % "1.0"
    ),
    mainClass in assembly := Some("nl.jworks.markdown_to_asciidoc.Converter")
  )

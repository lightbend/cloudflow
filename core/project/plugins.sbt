addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.9.9")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.1.1")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")
addSbtPlugin("com.lightbend.sbt" % "sbt-java-formatter" % "0.7.0")
// discipline
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.6")
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.7.0")

// publishing
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.1.2")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.9.12")

addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.5.0")

libraryDependencies ++= Seq(
  "org.codehaus.plexus" % "plexus-container-default" % "2.1.1",
  "org.codehaus.plexus" % "plexus-archiver" % "4.2.7")

addSbtPlugin("com.julianpeeters" % "sbt-avrohugger" % "2.0.0")
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.6")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.11"

addSbtPlugin("com.lucidchart" % "sbt-cross" % "4.0")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "1.2.0")

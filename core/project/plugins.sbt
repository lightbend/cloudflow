addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.8.0")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.1.1")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.10.0")
addSbtPlugin("com.lightbend.sbt" % "sbt-java-formatter" % "0.6.0")
// discipline
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.2")
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.6.0")
addSbtPlugin("com.lightbend" % "sbt-whitesource" % "0.1.18")

// publishing
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "2.0.1")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.8")

addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.4.3")

libraryDependencies ++= Seq(
  "org.codehaus.plexus" % "plexus-container-default" % "2.1.0",
  "org.codehaus.plexus" % "plexus-archiver" % "4.2.3")

addSbtPlugin("com.julianpeeters" % "sbt-avrohugger" % "2.0.0-RC18")
addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.31")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.10.2"

addSbtPlugin("com.lucidchart" % "sbt-cross" % "4.0")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.15.0")

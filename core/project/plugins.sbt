addSbtPlugin("com.lightbend.sbt" % "sbt-java-formatter"   % "0.5.0")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"         % "2.2.1")
addSbtPlugin("com.dwijnand"      % "sbt-dynver"           % "4.1.1")
addSbtPlugin("com.github.gseitz" % "sbt-release"          % "1.0.11")
addSbtPlugin("com.eed3si9n"      % "sbt-buildinfo"        % "0.9.0")
addSbtPlugin("net.virtual-void"  % "sbt-dependency-graph" % "0.9.2")
addSbtPlugin("com.julianpeeters" % "sbt-avrohugger"       % "2.0.0-RC18")
addSbtPlugin("com.lightbend.sbt" % "sbt-javaagent"        % "0.1.5")
addSbtPlugin("com.lightbend"     % "sbt-whitesource"      % "0.1.18")

addSbtPlugin("se.marcuslonnberg" % "sbt-docker"          % "1.8.0")
addSbtPlugin("com.typesafe.sbt"  % "sbt-native-packager" % "1.3.24")
addSbtPlugin("com.lightbend.sbt" % "sbt-javaagent"       % "0.1.5")
addSbtPlugin("de.heikoseeberger" % "sbt-header"          % "5.2.0")
addSbtPlugin("com.jsuereth"      % "sbt-pgp"             % "2.0.1")

// to generate one complete scaladoc site and one complete javadoc site
addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.4.3")

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.8")

addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.31")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.10.2"

// Resolver for the cloudflow-sbt plugin
//
resolvers += "Akka Snapshots".at("https://repo.akka.io/snapshots/")
resolvers += Resolver.url("cloudflow", url("https://lightbend.bintray.com/cloudflow"))(Resolver.ivyStylePatterns)
addSbtPlugin("com.lightbend.cloudflow" % "sbt-cloudflow" % "2.1.0-SNAPSHOT")

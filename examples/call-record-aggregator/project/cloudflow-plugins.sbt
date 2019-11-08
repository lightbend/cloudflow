// Resolver for the cloudflow-sbt plugin
//
// NOTE: Lightbend Commercial repository!
//  Please add your Lightbend Commercial download credentials to the global SBT config.
//
// Refer to https://github.com/lightbend/cloudflow-docs/blob/master/user-guide/getting-started.md
// for details on how to setup your Lightbend Commercial download credentials.
//
resolvers += Resolver.url("lightbend-oss", url("https://lightbend.bintray.com/cloudflow"))(Resolver.ivyStylePatterns)
resolvers += "Akka Snapshots" at "https://repo.akka.io/snapshots/"

addSbtPlugin("com.lightbend.cloudflow" % "sbt-cloudflow" % "1.3.0-SNAPSHOT")

// Resolver for the pipelines-sbt plugin
//
// NOTE: Private repository!
//  Please add your Bintray credentials to your global SBT config.
//
// Refer to https://developer.lightbend.com/docs/pipelines/current/#_installing
// for details on how to setup your Bintray credentials, which is required to access `sbt-pipelines`.
//

//resolvers += Resolver.url("lightbend-oss", url("https://lightbend.bintray.com/cloudflow"))(Resolver.ivyStylePatterns)
//resolvers += "Akka Snapshots" at "https://repo.akka.io/snapshots/"

addSbtPlugin("com.lightbend.cloudflow" % "sbt-cloudflow" % "1.3.0-M4")

// Resolver for the cloudflow-sbt plugin
resolvers += Resolver.url("cloudflow", url("https://lightbend.bintray.com/cloudflow"))(Resolver.ivyStylePatterns)

// Needs cloudflow from https://github.com/lightbend/cloudflow/pull/642
addSbtPlugin("com.lightbend.cloudflow" % "sbt-cloudflow" % "2.1.0-SNAPSHOT")

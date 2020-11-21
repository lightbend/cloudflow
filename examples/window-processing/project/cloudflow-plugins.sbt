// Resolver for the cloudflow-sbt plugin
//
resolvers += Resolver.url("cloudflow", url("https://lightbend.bintray.com/cloudflow"))(Resolver.ivyStylePatterns)

addSbtPlugin("com.lightbend.cloudflow" % "sbt-cloudflow" % "0.0.0-NIGHTLY20112020-1-d5f80418-20201120-1729")
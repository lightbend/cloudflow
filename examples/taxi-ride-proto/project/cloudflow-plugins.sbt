// Resolver for the cloudflow-sbt plugin
//
resolvers += Resolver.url("cloudflow", url("https://repo.lightbend.com/cloudflow"))(Resolver.ivyStylePatterns)

addSbtPlugin("com.lightbend.cloudflow" % "sbt-cloudflow" % "2.0.10")

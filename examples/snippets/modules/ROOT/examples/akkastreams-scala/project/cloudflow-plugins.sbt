// Resolver for the cloudflow-sbt plugin
val CloudflowBintrayURL = url("https://lightbend.bintray.com/cloudflow")
resolvers += Resolver.url("cloudflow", CloudflowBintrayURL )(Resolver.ivyStylePatterns) //<1>
addSbtPlugin("com.lightbend.cloudflow" % "sbt-cloudflow" % "2.0.0")                     //<2> 

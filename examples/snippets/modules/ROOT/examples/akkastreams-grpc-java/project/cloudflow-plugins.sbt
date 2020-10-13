// Resolver for the cloudflow-sbt plugin
resolvers += Resolver.url("cloudflow", url("https://lightbend.bintray.com/cloudflow"))(Resolver.ivyStylePatterns)

// Needs cloudflow from https://github.com/lightbend/cloudflow/pull/642
val latestVersion = {
  sys.env.get("CLOUDFLOW_VERSION").fold(
    sbtdynver.DynVer(None, "-", "v")
      .getGitDescribeOutput(new java.util.Date())
      .fold(throw new Exception("Failed to retrieve version"))(_.version("-"))
  )(identity)
}

addSbtPlugin("com.lightbend.cloudflow" % "sbt-cloudflow" % latestVersion)

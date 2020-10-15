val latestVersion = {
  sys.env.get("CLOUDFLOW_VERSION").fold(
    sbtdynver.DynVer(None, "-", "v")
      .getGitDescribeOutput(new java.util.Date())
      .fold(throw new Exception("Failed to retrieve version"))(_.version("-"))
  )(identity)
}

addSbtPlugin("com.lightbend.cloudflow" % "sbt-cloudflow" % latestVersion)

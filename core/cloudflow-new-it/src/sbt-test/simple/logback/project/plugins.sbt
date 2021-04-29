val cloudflowVersion = {
  if (sys.props.get("scripted").isDefined) {
    sys.props("cloudflow.version")
  } else {
    sys.env.get("CLOUDFLOW_VERSION").getOrElse("latest")
  }
}

addSbtPlugin("com.lightbend.cloudflow" % "sbt-cloudflow" % cloudflowVersion)

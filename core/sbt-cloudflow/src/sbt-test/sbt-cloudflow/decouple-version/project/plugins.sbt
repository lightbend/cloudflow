sys.props.get("plugin.version") match {
  case Some(x) => addSbtPlugin("com.lightbend.cloudflow" % "sbt-cloudflow" % x)
  case _ => sys.error("""|The system property 'plugin.version' is not defined.
                         |Specify this property using the scriptedLaunchOpts -D.""".stripMargin)
}

libraryDependencies += "com.lihaoyi" %% "ujson" % "0.9.5"

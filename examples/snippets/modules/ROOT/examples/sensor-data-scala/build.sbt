//tag::get-started[]
//tag::local-conf[]
lazy val sensorData =  (project in file("."))
    .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin)
    .settings(
      scalaVersion := "2.13.3",
      runLocalConfigFile := Some("src/main/resources/local.conf"), //<1>
      runLocalLog4jConfigFile := Some("src/main/resources/log4j.xml"), //<2>
      name := "sensor-data-scala",
//end::local-conf[]      

      libraryDependencies ++= Seq(
        Cloudflow.library.CloudflowAvro,
        "com.lightbend.akka"     %% "akka-stream-alpakka-file"  % "1.1.2",
        "com.typesafe.akka"      %% "akka-http-spray-json"      % "10.1.12",
        "ch.qos.logback"         %  "logback-classic"           % "1.4.4",
        "com.typesafe.akka"      %% "akka-http-testkit"         % "10.1.12" % "test",
        "org.scalatest"          %% "scalatest"                 % "3.0.8"  % "test"
      )
    )
//end::get-started[]
    .enablePlugins(ScalafmtPlugin)
    .settings(
      scalafmtOnCompile := true,

      organization := "com.lightbend.cloudflow",
      headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2020", "Lightbend Inc. <https://www.lightbend.com>")),

      crossScalaVersions := Vector(scalaVersion.value),
      scalacOptions ++= Seq(
        "-encoding", "UTF-8",
        "-target:jvm-1.8",
        "-Xlog-reflective-calls",
        "-Xlint",
        "-Ywarn-unused",
        "-deprecation",
        "-feature",
        "-language:_",
        "-unchecked"
      ),

      Compile / console / scalacOptions --= Seq("-Ywarn-unused"),
      Compile / sourceGenerators += (Compile / avroScalaGenerateSpecific).taskValue,
      Test / console / scalacOptions := (Compile / console / scalacOptions).value
    )

ThisBuild / dynverSeparator := "-"

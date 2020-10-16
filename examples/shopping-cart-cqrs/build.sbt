import sbt._
import sbt.Keys._

val AkkaVersion = "2.6.9"
val AkkaPersistenceCassandraVersion = "1.0.0"
val AkkaHttpVersion = "10.1.10"
val AkkaProjectionVersion = "1.0.0"

lazy val root =
  Project(id = "root", base = file("."))
    .enablePlugins(ScalafmtPlugin)
    .settings(
      name := "root",
      scalafmtOnCompile := true,
      skip in publish := true,
    )
    .withId("root")
    .settings(commonSettings)
    .aggregate(
      cloudflowShoppingCart,
      datamodel,
      akkaShoppingCart
    )

lazy val cloudflowShoppingCart = (project in file("./shopping-cart"))
  .enablePlugins(CloudflowApplicationPlugin)
  .settings(
    commonSettings,
    name := "shopping-cart-cqrs",
    libraryDependencies ++= Seq(
      "org.scalatest"  %% "scalatest"       % "3.0.7"    % "test"
      )
  )

lazy val datamodel = (project in file("./datamodel"))
  .enablePlugins(CloudflowLibraryPlugin)

lazy val akkaShoppingCartStreamlet = (project in file("./akka-shopping-cart-streamlet"))
  .enablePlugins(CloudflowAkkaPlugin)
  .settings(
    commonSettings,
    name := "akka-shopping-cart-streamlet",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-persistence-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-persistence-query" % AkkaVersion,
      "com.typesafe.akka" %% "akka-serialization-jackson" % AkkaVersion,
      "com.typesafe.akka" %% "akka-discovery" % AkkaVersion,
      "com.typesafe.akka" %% "akka-persistence-cassandra" % AkkaPersistenceCassandraVersion,
      "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % AkkaPersistenceCassandraVersion,
      "com.lightbend.akka" %% "akka-projection-eventsourced" % AkkaProjectionVersion,
      "com.lightbend.akka" %% "akka-projection-cassandra" % AkkaProjectionVersion,
      "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
      "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion % Test,
      "com.lightbend.akka" %% "akka-projection-testkit" % AkkaProjectionVersion % Test,
      "org.scalatest" %% "scalatest" % "3.1.0" % Test,
      "commons-io" % "commons-io" % "2.4" % Test,
      "org.scalatest"          %% "scalatest"              % "3.0.8"    % "test"
    )
  )
  .dependsOn(datamodel, akkaShoppingCart)

lazy val akkaShoppingCart = (project in file("./akka-shopping-cart"))
  .settings(
    commonSettings,
    name := "akka-shopping-cart",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-persistence-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-persistence-query" % AkkaVersion,
      "com.typesafe.akka" %% "akka-serialization-jackson" % AkkaVersion,
      "com.typesafe.akka" %% "akka-persistence-cassandra" % AkkaPersistenceCassandraVersion,
      "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % AkkaPersistenceCassandraVersion,
      "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,
      "ch.qos.logback" % "logback-classic" % "1.2.3"
      )
  )
  .dependsOn(datamodel)

lazy val commonSettings = Seq(
  scalaVersion := "2.12.11",
  scalacOptions ++= Seq(
    "-encoding", "UTF-8",
    "-target:jvm-1.8",
    "-Xlog-reflective-calls",
    "-Xlint",
    "-Ywarn-unused",
    "-Ywarn-unused-import",
    "-deprecation",
    "-feature",
    "-language:_",
    "-unchecked"
  ),
  resolvers ++= Seq("Alpakka Shapshots".at("https://dl.bintray.com/akka/snapshots")),

  scalacOptions in (Compile, console) --= Seq("-Ywarn-unused", "-Ywarn-unused-import"),
  scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value

)

import Versions._
import sbt._

object Dependencies {

  val akka            = "com.typesafe.akka"              %% "akka-actor"                % akkaVersion
  val akkaStreams     = "com.typesafe.akka"              %% "akka-stream"               % akkaVersion
  val akkaSprayJson   = "com.typesafe.akka"              %% "akka-http-spray-json"      % akkaHTTPJSONVersion
  val alpakkaFile     = "com.lightbend.akka"             %% "akka-stream-alpakka-file"  % alpakkaFileVersion

  val bijection       = "com.twitter"                    %% "bijection-avro"            % bijectionVersion
  val json2avro       = "tech.allegro.schema.json2avro"   % "converter"                 % json2javaVersion
  val tensorflow      = "org.tensorflow"                  % "tensorflow"                % tensorflowVersion
  val tensorflowProto = "org.tensorflow"                  % "proto"                     % tensorflowVersion
  val gson            = "com.google.code.gson"            % "gson"                      % gsonVersion
  val compress        = "org.apache.commons"              % "commons-compress"          % apacheCommonsCompress
  val scalajHTTP      = "org.scalaj"                     %% "scalaj-http"               % scalajHTTPVersion

  val slf4jAPI        = "org.slf4j"                       % "slf4j-api"                 % slf4jVersion
  // val slf4jSimple     = "org.slf4j"                       % "slf4j-simple"              % slf4jVersion
  val slf4jLog4J      = "org.slf4j"                       % "slf4j-log4j12"             % slf4jVersion
  // val scalaLogging    = "com.typesafe.scala-logging"     %% "scala-logging"             % scalaLoggingVersion
  val logback         = "ch.qos.logback"                  % "logback-classic"           % logbackClassicVersion

  val scalaTest       = "org.scalatest"                  %% "scalatest"                 % scaltestVersion    % "test"
  // Only used in tests:
  val SprayJson       = "io.spray"                       %% "spray-json"                % sprayJsonVersion   % "test"

  val logging = Seq(slf4jAPI, slf4jLog4J)
}

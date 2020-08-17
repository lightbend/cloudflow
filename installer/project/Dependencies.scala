import sbt._

object Dependencies {
  val AkkaVersion     = "2.5.25"
  val AkkaHttpVersion = "10.1.9"

  lazy val AkkaHttp          = "com.typesafe.akka" %% "akka-http"            % AkkaHttpVersion
  lazy val AkkaHttpSprayJson = "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion
  lazy val AkkaHttpTestkit   = "com.typesafe.akka" %% "akka-http-testkit"    % AkkaHttpVersion
  lazy val AkkaSlf4j         = "com.typesafe.akka" %% "akka-slf4j"           % AkkaVersion
  lazy val AkkaStream        = "com.typesafe.akka" %% "akka-stream"          % AkkaVersion
  lazy val AkkaStreamTestkit = "com.typesafe.akka" %% "akka-stream-testkit"  % AkkaVersion
  lazy val Ficus             = "com.iheart"        %% "ficus"                % "1.4.7"
  lazy val Logback           = "ch.qos.logback"     % "logback-classic"      % "1.2.3"
  lazy val OsLib             = "com.lihaoyi"       %% "os-lib"               % "0.4.2"
  lazy val ScalaCheck        = "org.scalacheck"    %% "scalacheck"           % "1.14.0"
  lazy val ScalaTest         = "org.scalatest"     %% "scalatest"            % "3.0.8"
  lazy val Skuber            = "io.skuber"         %% "skuber"               % "2.4.1-cve-fix-a8d7617c"
  lazy val TypesafeConfig    = "com.typesafe"       % "config"               % "1.4.0"
  lazy val ZtExec            = "org.zeroturnaround" % "zt-exec"              % "1.11"
}

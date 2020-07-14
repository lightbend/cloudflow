import sbt._
import sbt.Keys._

import Dependencies._

ThisBuild / version := "1.0.0-SNAPSHOT"
ThisBuild / organization := "com.lightbend"
ThisBuild / organizationName := "Lightbend, Inc"

lazy val root = Project("cloudflow-installer", file("."))
  .enablePlugins(
    sbtdocker.DockerPlugin,
    JavaAppPackaging,
    BuildNumberPlugin,
    BuildInfoPlugin
  )
  .settings(
    libraryDependencies ++= Vector(
          AkkaHttpSprayJson,
          AkkaSlf4j,
          AkkaStream,
          AkkaStreamTestkit % "test",
          Ficus,
          Logback,
          OsLib,
          ScalaTest % "test",
          Skuber,
          TypesafeConfig,
          ZtExec
        )
  )
  .settings(
    scalaVersion := "2.12.11",
    crossScalaVersions := Vector(scalaVersion.value),
    organization := "com.lightbend.cloudflow",
    skip in publish := true,
    mainClass in Compile := Some("cloudflow.installer.Main"),
    compile in Compile := (compile in Compile).dependsOn(YamlGeneratorTask.task).value,
    publishArtifact in (Compile, packageDoc) := false,
    publishArtifact in (Compile, packageSrc) := false,
    buildOptions in docker := BuildOptions(
          cache = true,
          removeIntermediateContainers = BuildOptions.Remove.OnSuccess,
          pullBaseImage = BuildOptions.Pull.IfMissing
          // TODO: "Always" won't work unless you have used `docker login` with
          // your own service/user account. We should move to use some
          // way to call `gcloud docker --` instead.
          // pullBaseImage = BuildOptions.Pull.Always
        ),
    imageNames in docker := Seq(
          ImageName(
            registry = Some("docker.io"),
            namespace = Some("lightbend"),
            repository = "cloudflow-installer",
            tag = Some(buildNumber.value.asVersion)
          )
        ),
    dockerfile in docker := {
      val appDir: File = stage.value
      val targetDir    = "/app"
      new Dockerfile {
        from("marketplace.gcr.io/google/ubuntu1804")
        entryPoint(s"$targetDir/bin/${executableScriptName.value}")
        copy(appDir, targetDir, chown = "daemon:daemon")
        copy(baseDirectory(_ / "yaml" / "kustomize").value, targetDir ++ "/yaml/kustomize")
        workDir(targetDir)
        runRaw(
            """apt-get update && apt-get install -y \
    wget \
 && rm -rf /var/lib/apt/lists/*"""
        )
        runRaw(
          "curl -LO https://storage.googleapis.com/kubernetes-release/release/`curl -s https://storage.googleapis.com/kubernetes-release/release/stable.txt`/bin/linux/amd64/kubectl && chmod +x ./kubectl && mv ./kubectl /usr/local/bin/kubectl"
        )
        runRaw(
          "wget -q https://github.com/openshift/origin/releases/download/v3.11.0/openshift-origin-client-tools-v3.11.0-0cbc58b-linux-64bit.tar.gz -O - | tar -zxf - --strip-components=1 --exclude kubectl -C /usr/local/bin"
        )
      }
    },
    Test / fork := true,
    scalacOptions ++= Seq(
          "-encoding",
          "UTF-8",
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
    scalacOptions in (Compile, console) := (scalacOptions in (Global)).value.filter(_ == "-Ywarn-unused-import"),
    scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value
  )
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](
          name,
          version,
          scalaVersion,
          sbtVersion,
          BuildInfoKey.action("buildTime") {
            java.time.Instant.now().toString
          },
          BuildInfoKey.action("buildUser") {
            sys.props.getOrElse("user.name", "unknown")
          }
        ),
    buildInfoPackage := "cloudflow.installer"
  )

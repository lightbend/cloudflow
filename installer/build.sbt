import sbt._
import sbt.Keys._

import Dependencies._
import sbtdocker.Instructions

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
    resolvers += Resolver.url("cloudflow", url("https://lightbend.bintray.com/cloudflow"))(Resolver.ivyStylePatterns),
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
    //creates a docker images
    imageTag(sys.props.get("dockerfile")),
    dockerfileDistro(sys.props.get("dockerfile")),
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






def imageTag(dockerfile: Option[String]): Def.Setting[sbt.Task[Seq[sbtdocker.ImageName]]] = {
  val tagSuffix = if (dockerfile.isDefined && dockerfile.get == "gcp-marketplace") "-gcp-marketplace"    
            else ""

    imageNames in docker := Seq(
          ImageName(
            registry = Some("docker.io"),
            namespace = Some("lightbend"),
            repository = "cloudflow-installer",
            tag = Some(s"${buildNumber.value.asVersion}$tagSuffix")
          )
    )
}

def dockerfileDistro(dockerfile: Option[String]): Def.Setting[sbt.Task[sbtdocker.DockerfileBase]] = {
  if (dockerfile.isDefined && dockerfile.get == "gcp-marketplace")  gcpMarketplaceDockerfile    
  else ossDockerfile  
}

lazy val gcpMarketplaceDockerfile: Def.Setting[sbt.Task[sbtdocker.DockerfileBase]] = {
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
               openjdk-8-jdk \
               && rm -rf /var/lib/apt/lists/*"""
        )
        runRaw(
          "curl -LO https://storage.googleapis.com/kubernetes-release/release/`curl -s https://storage.googleapis.com/kubernetes-release/release/stable.txt`/bin/linux/amd64/kubectl && chmod +x ./kubectl && mv ./kubectl /usr/local/bin/kubectl"
        )
        runRaw(
          "wget -q https://github.com/openshift/okd/releases/download/4.5.0-0.okd-2020-07-14-153706-ga/openshift-client-linux-4.5.0-0.okd-2020-07-14-153706-ga.tar.gz -O - | tar -zxf - --exclude kubectl -C /usr/local/bin"
        )
      }
    }
  }


//alpine implementation, currently default one
lazy val ossDockerfile: Def.Setting[sbt.Task[sbtdocker.DockerfileBase]] = {
    dockerfile in docker := {
      val appDir: File = stage.value
      val targetDir    = "/app"
      new Dockerfile {
        from("adoptopenjdk/openjdk8:alpine")
        entryPoint(s"$targetDir/bin/${executableScriptName.value}")
        copy(appDir, targetDir, chown = "daemon:daemon")
        copy(baseDirectory(_ / "yaml" / "kustomize").value, targetDir ++ "/yaml/kustomize")
        workDir(targetDir)
        runRaw("apk update && apk add wget bash curl")
        runRaw(
          "curl -LO https://storage.googleapis.com/kubernetes-release/release/`curl -s https://storage.googleapis.com/kubernetes-release/release/stable.txt`/bin/linux/amd64/kubectl && chmod +x ./kubectl && mv ./kubectl /usr/local/bin/kubectl"
        )
        runRaw(
          "wget -q https://github.com/openshift/okd/releases/download/4.5.0-0.okd-2020-07-14-153706-ga/openshift-client-linux-4.5.0-0.okd-2020-07-14-153706-ga.tar.gz -O - | tar -zxf - --exclude kubectl -C /usr/local/bin"
        )
      }
    } 
  }

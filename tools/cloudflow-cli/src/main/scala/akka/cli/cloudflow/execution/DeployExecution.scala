/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import java.io.File

import scala.util.{ Failure, Success, Try }
import akka.cloudflow.Json
import akka.cli.cloudflow.{ Cli, CliException, CliLogger, DeployResult, Execution }
import akka.cli.cloudflow.kubeclient.KubeClient
import akka.datap.crd.App
import akka.cli.cloudflow.commands.Deploy

object DeployExecution {

  private final case class StreamletVersion(required: String, thunk: () => Try[String])

}

final case class DeployExecution(d: Deploy, client: KubeClient, logger: CliLogger)
    extends Execution[DeployResult]
    with WithProtocolVersion
    with WithUpdateReplicas
    with WithConfiguration {
  import DeployExecution._

  private def applicationDescriptorValidation(crApp: App.Cr): Try[Unit] = {
    crApp.spec.version match {
      case None =>
        Failure(CliException("Application file parse error: spec.version is missing or empty"))

      case Some(versionStr) =>
        for {
          version <- Try {
            require { !versionStr.contains(' ') }
            Integer.parseInt(versionStr)
          }.recoverWith {
            case _ => Failure(CliException("Application file parse error: spec.version is invalid"))
          }
          libraryVersion <- Try {
            val libraryVersion = crApp.spec.libraryVersion.get
            require { !libraryVersion.contains(' ') }
            libraryVersion
          }.recoverWith {
            case _ =>
              Failure(CliException("Application file parse error: spec.library_version is missing, empty or invalid"))
          }
        } yield {
          lazy val lvMsg = s"built with sbt-cloudflow version ${libraryVersion},"

          version match {
            case v if Cli.SupportedApplicationDescriptorVersion > v =>
              Failure(CliException(
                s"Application ${lvMsg} is incompatible and requires a newer version of the kubectl cloudflow plugin. Please upgrade and try again"))
            case v if Cli.SupportedApplicationDescriptorVersion < v =>
              Failure(CliException(
                s"Application ${lvMsg} is incompatible and no longer supported. Please upgrade sbt-cloudflow and rebuild the application with 'sbt buildApp'"))
            case _ => Success(())
          }
        }
    }
  }

  private def loadCrFile(f: File) =
    Try {
      Json.mapper.readValue(f, classOf[App.Cr])
    }.recoverWith {
      case ex =>
        Failure(
          CliException(
            "Failed to read the file contents for the CR - please check if the file exists or it has a bad formatting",
            ex))
    }

  private def validateStreamlet(name: String, required: String)(
      getVersion: () => Try[String]): Option[(String, Option[Throwable])] = {
    getVersion() match {
      case Success(version) if (version != required) =>
        Some((
          s"${name} is installed but does not support the required version of the CRD, required ${required}, installed ${version}",
          None))
      case Failure(ex) =>
        Some(
          (
            s"cannot detect that ${name} is installed, please install it at version ${required} before continuing",
            Some(ex)))
      case _ => None
    }
  }

  private def validateStreamletsDependencies(
      crApp: App.Cr,
      streamletChecks: Map[String, StreamletVersion]): Try[Unit] = {
    val res = crApp.spec.streamlets
      .map(_.descriptor.runtime)
      .distinct
      .map { s =>
        streamletChecks.get(s).fold[Option[(String, Option[Throwable])]](None) { check =>
          validateStreamlet(s, check.required)(check.thunk)
        }
      }
      .flatten

    if (!res.isEmpty) {
      val ex: Throwable = res.map(_._2).flatten.headOption.getOrElse(null)
      Failure(CliException(res.map(_._1).mkString("\n"), ex))
    } else {
      Success(())
    }
  }

  private def referencedKafkaSecretExists(appCr: App.Cr, kafkaClusters: () => Try[List[String]]): Try[Unit] = {
    val expectedClusters = appCr.spec.deployments.map(_.portMappings.values.map(_.cluster)).flatten.flatten.distinct

    if (expectedClusters.nonEmpty) {
      (for {
        availableClusters <- kafkaClusters()
      } yield {
        expectedClusters.diff(availableClusters) match {
          case Nil => Success(())
          case missings =>
            Failure(
              CliException(
                s"Could not find the kafka cluster configurations: [${missings.mkString(", ")}] referenced in the Cr"))
        }
      }).flatten
    } else { Success(()) }
  }

  private def getImageReference(crApp: App.Cr) = {
    if (crApp.spec.deployments.size < 1) {
      Failure(CliException("The application specification doesn't contains deployments"))
    } else {
      // Get the first available image, all images must be present in the same repository.
      val imageRef = crApp.spec.deployments(0).image

      Image(imageRef)
    }
  }

  def run(): Try[DeployResult] = {
    logger.info("Executing command Deploy")
    for {
      // Default protocol validation
      version <- validateProtocolVersion(client)

      // prepare the data
      localApplicationCr <- loadCrFile(d.crFile)

      // update the replicas
      currentAppCr <- client.readCloudflowApp(localApplicationCr.spec.appId)
      clusterReplicas = getStreamletsReplicas(currentAppCr)
      clusterApplicationCr <- updateReplicas(localApplicationCr, clusterReplicas)
      applicationCr <- updateReplicas(clusterApplicationCr, d.scales)

      image <- getImageReference(applicationCr)

      logbackContent = readLogbackContent(d.logbackConfig)
      // configuration validation
      (cloudflowConfig, configStr) <- generateConfiguration(
        d.aggregatedConfig,
        applicationCr,
        logbackContent,
        () => client.getPvcs(namespace = applicationCr.spec.appId))

      // validation of the CR
      _ <- applicationDescriptorValidation(applicationCr)
      streamletChecks = Map(
        ("spark", StreamletVersion(Cli.RequiredSparkVersion, (() => client.sparkAppVersion()))),
        ("flink", StreamletVersion(Cli.RequiredFlinkVersion, (() => client.flinkAppVersion()))))
      _ <- validateStreamletsDependencies(applicationCr, streamletChecks)

      // validate the Cr against the cluster
      _ <- referencedKafkaSecretExists(
        applicationCr,
        () => client.getKafkaClusters(namespace = Some(applicationCr.spec.appId)).map(_.keys.toList))

      // streamlets configurations
      streamletsConfigs <- streamletsConfigs(
        applicationCr,
        cloudflowConfig,
        () => client.getKafkaClusters(None).map(parseValues))

      // Operations on the cluster
      name = applicationCr.spec.appId
      _ <- client.createNamespace(name)
      _ <- {
        if (d.noRegistryCredentials) Success(())
        else {
          client.createImagePullSecret(
            namespace = name,
            dockerRegistryURL = image.registry.getOrElse(""),
            dockerUsername = d.dockerUsername,
            dockerPassword = d.dockerPassword)
        }
      }
      uid <- client.createCloudflowApp(applicationCr.spec)
      _ <- client.configureCloudflowApp(
        name,
        uid,
        configStr,
        logbackContent,
        version == Cli.ProtocolVersion,
        streamletsConfigs)
    } yield {
      logger.trace("Command Deploy executed successfully")
      DeployResult()
    }
  }
}

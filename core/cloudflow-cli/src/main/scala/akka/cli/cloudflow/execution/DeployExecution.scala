/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import java.io.File
import scala.util.{ Failure, Success, Try }
import com.typesafe.config.{ ConfigFactory, ConfigRenderOptions }
import akka.cli.cloudflow.{ Cli, CliException, CliLogger, DeployResult, Execution, Json }
import akka.cli.cloudflow.kubeclient.KubeClient
import akka.datap.crd.App
import akka.cli.cloudflow.commands.Deploy

import scala.jdk.CollectionConverters._

object DeployExecution {

  private final case class StreamletVersion(required: String, thunk: () => Try[String])

}

final case class DeployExecution(d: Deploy, client: KubeClient, logger: CliLogger)
    extends Execution[DeployResult]
    with WithProtocolVersion
    with WithUpdateReplicas
    with WithUpdateVolumeMounts
    with WithConfiguration {
  import DeployExecution._

  private def applicationDescriptorValidation(crApp: App.Cr): Try[Unit] = {
    crApp.getSpec.version match {
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
            val libraryVersion = crApp.getSpec.libraryVersion.get
            require { !libraryVersion.contains(' ') }
            libraryVersion
          }.recoverWith {
            case _ =>
              Failure(CliException("Application file parse error: spec.library_version is missing, empty or invalid"))
          }
        } yield {
          lazy val lvMsg = s"built with sbt-cloudflow version ${libraryVersion},"

          version match {
            case v if Cli.ApplicationDescriptorVersion > v =>
              Failure(CliException(
                s"Application ${lvMsg} is incompatible and requires a newer version of the kubectl cloudflow plugin. Please upgrade and try again"))
            case v if Cli.ApplicationDescriptorVersion < v =>
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

  private def referencedKafkaSecretExists(appCr: App.Cr, kafkaClusters: () => Try[List[String]]): Try[Unit] = {
    val expectedClusters = appCr.getSpec.deployments.flatMap(_.portMappings.values.map(_.cluster)).flatten.distinct

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
    if (crApp.getSpec.deployments.size < 1) {
      Failure(CliException("The application specification doesn't contains deployments"))
    } else {
      // Get the first available image, all images must be present in the same repository.
      val imageRef = crApp.getSpec.deployments(0).image

      Image(imageRef)
    }
  }

  def run(): Try[DeployResult] = {
    logger.info("Executing command Deploy")
    for {
      // Default protocol validation
      _ <- validateProtocolVersion(client, d.operatorNamespace, logger)

      // prepare the data
      baseApplicationCr <- loadCrFile(d.crFile)
      localApplicationCr = {
        d.serviceAccount match {
          case Some(sa) => baseApplicationCr.copy(_spec = baseApplicationCr.getSpec.copy(serviceAccount = Some(sa)))
          case _        => baseApplicationCr
        }
      }
      namespace = d.namespace.getOrElse(localApplicationCr.getSpec.appId)

      // update the replicas
      currentAppCr <- client.readCloudflowApp(localApplicationCr.getSpec.appId, namespace)
      clusterReplicas = getStreamletsReplicas(currentAppCr)
      clusterApplicationCr <- updateReplicas(localApplicationCr, clusterReplicas)
      applicationCrReplicas <- updateReplicas(clusterApplicationCr, d.scales)
      applicationCr <- updateVolumeMounts(
        applicationCrReplicas,
        d.volumeMounts,
        () => client.getPvcs(namespace = namespace))

      image <- getImageReference(applicationCr)

      logbackContent = readLogbackContent(d.logbackConfig)
      // configuration validation
      (cloudflowConfig, configStr) <- generateConfiguration(
        d.aggregatedConfig,
        applicationCr,
        logbackContent,
        () => client.getPvcs(namespace = namespace))

      // validation of the CR
      _ <- applicationDescriptorValidation(applicationCr)
      // validate the Cr against the cluster
      _ <- referencedKafkaSecretExists(
        applicationCr,
        () => client.getKafkaClusters(namespace = d.operatorNamespace).map(_.keys.toList))

      // streamlets configurations
      streamletsConfigs <- streamletsConfigs(applicationCr, cloudflowConfig, () => {
        client.getKafkaClusters(namespace = d.operatorNamespace).map(parseValues)
      })

      // Operations on the cluster
      name = applicationCr.getSpec.appId
      _ <- client.createNamespace(namespace)
      _ <- {
        if (d.noRegistryCredentials) Success(())
        else {
          client.createImagePullSecret(
            namespace = namespace,
            dockerRegistryURL = image.registry.getOrElse(""),
            dockerUsername = d.dockerUsername,
            dockerPassword = d.dockerPassword)
        }
      }
      uid <- client.createCloudflowApp(applicationCr.getSpec, namespace)
      _ <- client.configureCloudflowApp(name, namespace, uid, configStr, logbackContent, streamletsConfigs)
    } yield {
      logger.trace("Command Deploy executed successfully")
      DeployResult()
    }
  }
}

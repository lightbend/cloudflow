/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import java.io.File
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try }
import scala.util.hashing.MurmurHash3
import akka.cli.cloudflow.{ CliException, CliLogger }
import akka.cloudflow.config.{ CloudflowConfig, UnsafeCloudflowConfigLoader }
import akka.datap.crd.App
import com.typesafe.config.{ Config, ConfigFactory, ConfigRenderOptions }
import io.fabric8.kubernetes.client.utils.Serialization

private final case class StreamletConfigs(streamlet: Config, runtime: Config, pods: Config, application: Config)

trait WithConfiguration {
  val logger: CliLogger

  // TODO: when names are finalized run the GraalVM assisted config
  private def applicationRunnerConfig(appId: String, appVersion: String, deployment: App.Deployment): Config = {
    val configStreamlet =
      cloudflow.runner.config.Streamlet(
        streamletRef = deployment.streamletName,
        className = deployment.className,
        context = cloudflow.runner.config.StreamletContext(
          appId = appId,
          appVersion = appVersion,
          config = deployment.config,
          volumeMounts = Option(deployment.volumeMounts).getOrElse(Seq.empty).map { vm =>
            cloudflow.runner.config.VolumeMount(name = vm.name, path = vm.path, accessMode = vm.accessMode)
          },
          portMappings = Option(deployment.portMappings).getOrElse(Map.empty).map {
            case (name, pm) =>
              name -> cloudflow.runner.config.Topic(id = pm.id, cluster = pm.cluster, config = pm.config)
          }))

    ConfigFactory.parseString(cloudflow.runner.config.toJson(configStreamlet))
  }

  private def referencedPvcsExists(
      cloudflowConfig: CloudflowConfig.CloudflowRoot,
      pvcs: () => Try[List[String]]): Try[Unit] = {
    val streamletPvcs = (for {
      streamlets <- cloudflowConfig.cloudflow.streamlets.values
      pods <- streamlets.kubernetes.pods.values
      volume <- pods.volumes.values
    } yield {
      volume match {
        case CloudflowConfig.PvcVolume(name, _) => Some(name)
        case _                                  => None
      }
    }).flatten

    val runtimePvcs = (for {
      streamlets <- cloudflowConfig.cloudflow.runtimes.values
      pods <- streamlets.kubernetes.pods.values
      volume <- pods.volumes.values
    } yield {
      volume match {
        case CloudflowConfig.PvcVolume(name, _) => Some(name)
        case _                                  => None
      }
    }).flatten

    val allPvcs = (streamletPvcs ++ runtimePvcs).toSeq.distinct

    if (allPvcs.nonEmpty) {
      (for {
        v <- pvcs()
      } yield {
        logger.trace(s"Found PVCs are ${v.mkString("[", ",", "]")}")
        allPvcs.diff(v) match {
          case Nil =>
            Success(())
          case missings =>
            Failure(
              CliException(
                s"Configuration contains pvcs: [${missings.mkString(", ")}] that are not present in the namespace"))
        }
      }).flatten
    } else { Success(()) }
  }

  private def validateConfiguredStreamlets(crApp: App.Cr, cloudflowConfig: CloudflowConfig.CloudflowRoot): Try[Unit] = {
    val configStreamlets = cloudflowConfig.cloudflow.streamlets.keys.toSeq.distinct
    val crStreamlets = crApp.getSpec.streamlets.map(_.name).toSeq.distinct

    configStreamlets.diff(crStreamlets) match {
      case Nil => Success(())
      case missings =>
        Failure(CliException(s"Configuration contains streamlets: [${missings
          .mkString(", ")}] that are not present in the CR, available streamlets are [${crStreamlets.mkString(", ")}]"))
    }
  }

  private def validateTopicIds(crApp: App.Cr, cloudflowConfig: CloudflowConfig.CloudflowRoot): Try[Unit] = {
    val configTopics = cloudflowConfig.cloudflow.topics.keys.toSeq.distinct
    val crTopics = crApp.getSpec.deployments.flatMap(_.portMappings.values.map(_.id)).distinct

    configTopics.diff(crTopics) match {
      case Nil => Success(())
      case missings =>
        Failure(CliException(
          s"Configuration contains topics: [${missings.mkString(", ")}] that are not present in the application spec"))
    }
  }

  private def validateConfigParameter(param: App.ConfigParameterDescriptor, config: Config, streamlet: String): Unit = {
    val errorPrefix = s"Configuration ${param.key} for streamlet ${streamlet} is not valid"
    param.validationType match {
      case "bool" =>
        try { config.getBoolean(param.key) }
        catch {
          case ex: Throwable => throw CliException(s"$errorPrefix, expected a boolean value", ex)
        }
      case "int32" =>
        try { Integer.parseInt(config.getString(param.key)) }
        catch {
          case ex: Throwable => throw CliException(s"$errorPrefix, expected an integer value", ex)
        }
      case "double" =>
        try { config.getDouble(param.key) }
        catch {
          case ex: Throwable => throw CliException(s"$errorPrefix, expected a double value", ex)
        }
      case "string" =>
        val regexp =
          try { param.validationPattern.r }
          catch {
            case ex: Throwable => throw CliException(s"Validation RegExp for ${param.key} is not valid", ex)
          }
        val value =
          try {
            config.getString(param.key)
          } catch {
            case ex: Throwable => throw CliException(s"$errorPrefix, expected a string value", ex)
          }
        if (!param.validationPattern.isEmpty && !regexp.matches(value)) {
          throw CliException(
            s"Configuration ${param.key} for streamlet ${streamlet}, with value '${value}' doesn't match the regular expression '${param.validationPattern}'")
        }
      case "duration" =>
        try { config.getDuration(param.key) }
        catch {
          case ex: Throwable => throw CliException(s"$errorPrefix, expected a duration value", ex)
        }
      case "memorysize" =>
        try { config.getMemorySize(param.key) }
        catch {
          case ex: Throwable => throw CliException(s"$errorPrefix, expected a memory size value", ex)
        }
      case invalidType =>
        throw CliException(
          s"Encountered an unknown validation type `${invalidType}`. Please make sure that the CLI is up-to-date.")
    }
  }

  def validateConfigParameters(crApp: App.Cr, cloudflowConfig: CloudflowConfig.CloudflowRoot): Try[Unit] = {
    Try {
      crApp.getSpec.streamlets.foreach { streamlet =>
        cloudflowConfig.cloudflow.streamlets.get(streamlet.name).foreach { s =>
          val configParameters = streamlet.descriptor.configParameters
          val detectedConfigParameters =
            s.configParameters.entrySet().asScala.map(_.getKey).filter(!_.contains('.')).toSeq
          detectedConfigParameters.diff(configParameters.map(_.key)) match {
            case Nil =>
            case unmatched =>
              throw CliException(s"Streamlet ${streamlet.name} contains undeclared configuration parameters ${unmatched
                .mkString("[", ", ", "]")}")
          }
          configParameters.foreach { parameter =>
            if (s.configParameters.hasPath(parameter.key)) {
              validateConfigParameter(parameter, s.configParameters, streamlet.name)
            }
          }
        }
      }
    }
  }

  def readLogbackContent(f: Option[File]): Option[String] = {
    f.map { file =>
      val source = Source.fromFile(file)
      try {
        source.getLines().mkString
      } finally {
        source.close()
      }
    }
  }

  def render(config: Config): String = {
    config
      .root()
      .render(ConfigRenderOptions.concise())
  }

  def parseValues(in: Map[String, String]): Map[String, Config] = {
    in.map { case (k, v) => k -> ConfigFactory.parseString(v) }
  }

  def generateConfiguration(
      tryUserConfig: => Try[Config],
      appCr: App.Cr,
      loggingConfig: Option[String],
      pvcsFn: () => Try[List[String]]): Try[(CloudflowConfig.CloudflowRoot, String)] = {
    lazy val pvcs = pvcsFn()
    for {
      userConfig <- tryUserConfig
      cloudflowConfig <- CloudflowConfig.loadAndValidate(userConfig)
      defaultConfig = CloudflowConfig.defaultConfig(appCr.getSpec)
      defaultMounts = CloudflowConfig.defaultMountsConfig(appCr.getSpec, allowedRuntimes = List("flink", "spark"))
      config = userConfig
        .withFallback(CloudflowConfig.writeConfig(defaultConfig))
        .withFallback(CloudflowConfig.writeConfig(defaultMounts))
        .withFallback {
          loggingConfig match {
            case Some(s) =>
              CloudflowConfig.writeConfig(
                CloudflowConfig.loggingMountsConfig(appCr.getSpec, s"${MurmurHash3.stringHash(s)}"))
            case None => ConfigFactory.empty()
          }
        }
        .resolve()
      resultingConfig <- UnsafeCloudflowConfigLoader.load(config)

      // validate CR against configuration
      _ <- validateConfiguredStreamlets(appCr, cloudflowConfig)
      _ <- validateTopicIds(appCr, cloudflowConfig)
      _ <- validateConfigParameters(appCr, cloudflowConfig)

      // validate configuration against cluster
      _ <- referencedPvcsExists(cloudflowConfig, () => pvcs)
      _ <- referencedPvcsExists(defaultMounts, () => pvcs)
    } yield {
      (resultingConfig, render(config))
    }
  }

  private val DefaultConfigurationName = "default"

  def portMappings(
      deployment: App.Deployment,
      appConfig: CloudflowConfig.CloudflowRoot,
      streamletConfig: Config,
      clusterSecretConfigs: Map[String, Config]) = {
    val defaultClusterConfig = clusterSecretConfigs.get(DefaultConfigurationName)
    // Adding bootstrap-servers key for backwards compatibility
    val kafkaBootstrapServersCompat2010 = defaultClusterConfig.map(_.getString("bootstrap.servers"))

    val portMappingConfigs = deployment.portMappings.flatMap {
      case (portName, portMapping) =>
        Try {
          val configCluster =
            appConfig.cloudflow.topics.get(portName).flatMap(_.cluster)

          val clusterSecretConfig = {
            configCluster
              .flatMap(clusterName => clusterSecretConfigs.get(clusterName))
              .orElse(
                portMapping.cluster
                  .flatMap(clusterName => clusterSecretConfigs.get(clusterName))
                  .orElse(defaultClusterConfig))
              .getOrElse(ConfigFactory.empty())
          }

          val portMappingConfig = {
            appConfig.cloudflow.topics.find { case (k, _) => k == portMapping.id } match {
              case Some((_, config)) =>
                ConfigFactory.empty().withFallback(CloudflowConfig.writeTopic(config))
              case _ => ConfigFactory.empty().withFallback(CloudflowConfig.writeConfig(appConfig))
            }
          }

          val originalPortMappingconfig =
            if (portMapping.config != null) {
              ConfigFactory
                .parseString(
                  Serialization
                    .jsonMapper()
                    .writeValueAsString(portMapping.config))
            } else {
              ConfigFactory.empty()
            }

          val portMappingWithFallbackConfig = portMappingConfig
            .withFallback(originalPortMappingconfig)
            .withFallback(clusterSecretConfig)

          // format: off
          CloudflowConfig.CloudflowRoot(
            CloudflowConfig.Cloudflow(
              runner = Some(CloudflowConfig.StreamletContext(
                streamlet = CloudflowConfig.Context(
                  CloudflowConfig.PortMappings(
                    Map(portName -> CloudflowConfig.PortMapping(
                      id = portMapping.id,
                      config = portMappingWithFallbackConfig))))))))
          // format: on
        }.toOption
    }

    val conf = portMappingConfigs
      .foldLeft(streamletConfig) { (acc, el) =>
        acc.withFallback(CloudflowConfig.writeConfig(el))
      }
    kafkaBootstrapServersCompat2010
      .map { bs =>
        conf.withFallback(ConfigFactory.parseString(s"""cloudflow.kafka.bootstrap-servers="${bs}""""))
      }
      .getOrElse(conf)
  }

  val SecretDataKey = "secret.conf"
  val RuntimeConfigDataKey = "runtime-config.conf"
  val PodsConfigDataKey = "pods-config.conf"
  val ApplicationDataKey = "application.conf"

  def streamletsConfigs(
      appCr: App.Cr,
      appConfig: CloudflowConfig.CloudflowRoot,
      clusterSecretConfigs: () => Try[Map[String, Config]]): Try[Map[App.Deployment, Map[String, String]]] = {

    val allReferencedClusters = {
      appConfig.cloudflow.topics.flatMap { case (_, topic) => topic.cluster } ++
      appCr.getSpec.deployments.flatMap(_.portMappings.values.flatMap(_.cluster)) ++
      Seq(DefaultConfigurationName)
    }

    for {
      clusterSecrets <- clusterSecretConfigs()
      clustersConfig <- Try {
        allReferencedClusters.flatMap { name =>
          clusterSecrets.find { case (k, _) => k == name } match {
            case Some((_, secret)) =>
              Some(name -> secret)
            case _ =>
              throw new CliException(
                s"The referenced cluster configuration secret '$name' for app '${appCr.name}' does not exist.")
          }
        }.toMap
      }
      res <- Try {
        appCr.getSpec.deployments.map { deployment =>
          val streamletConfig = CloudflowConfig.streamletConfig(deployment.streamletName, deployment.runtime, appConfig)
          val streamletWithPortMappingsConfig = portMappings(deployment, appConfig, streamletConfig, clustersConfig)
          val applicationConfig = applicationRunnerConfig(appCr.name, appCr.getSpec.appVersion, deployment)
          deployment -> StreamletConfigs(
            streamlet = streamletWithPortMappingsConfig,
            runtime = CloudflowConfig.runtimeConfig(deployment.streamletName, deployment.runtime, appConfig),
            pods = CloudflowConfig.podsConfig(deployment.streamletName, deployment.runtime, appConfig),
            application = applicationConfig)
        }.toMap
      }
    } yield {
      res.map {
        case (k, v) =>
          k -> Map(
            ApplicationDataKey -> render(v.application),
            SecretDataKey -> render(v.streamlet),
            RuntimeConfigDataKey -> render(v.runtime),
            PodsConfigDataKey -> render(v.pods))
      }
    }
  }
}

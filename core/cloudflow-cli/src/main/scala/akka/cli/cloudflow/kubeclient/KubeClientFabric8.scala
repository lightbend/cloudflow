/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.kubeclient

import java.io.File
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try }
import akka.cli.cloudflow.kubeclient.KubeClient._
import akka.datap.crd.App
import akka.cli.cloudflow.{ models, CliException, CliLogger }
import akka.cli.common.Base64Helper
import akka.cli.microservice.{ AkkaMicroservice, AkkaMicroserviceList, AkkaMicroserviceSpec }
import buildinfo.BuildInfo
import com.fasterxml.jackson.annotation.{ JsonCreator, JsonProperty }
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.fabric8.kubernetes.api.model.{
  LocalObjectReferenceBuilder,
  NamespaceBuilder,
  ObjectMetaBuilder,
  OwnerReference,
  OwnerReferenceBuilder,
  SecretBuilder,
  ServiceAccountBuilder
}
import io.fabric8.kubernetes.client.dsl.{ MixedOperation, Resource }
import io.fabric8.kubernetes.client.utils.Serialization
import io.fabric8.kubernetes.client.{ Config, DefaultKubernetesClient, KubernetesClient }

import scala.io.Source
import scala.sys.process.{ Process, ProcessIO }

object KubeClientFabric8 {
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  private final case class DockerConfig(
      @JsonProperty("auths")
      auths: Map[String, DockerConfigEntry])
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  private final case class DockerConfigEntry(
      @JsonProperty("username")
      username: String,
      @JsonProperty("password")
      password: String,
      @JsonProperty("auth")
      auth: String)
}

class KubeClientFabric8(
    val config: Option[File],
    clientFactory: Config => KubernetesClient = new DefaultKubernetesClient(_))(implicit val logger: CliLogger)
    extends KubeClient {
  import KubeClientFabric8._

  private lazy val kubeClient = Try {
    def getConfig() = {
      config
        .map { file =>
          Config.fromKubeconfig(Source.fromFile(file).getLines().mkString("\n"))
        }
        .getOrElse(Config.autoConfigure(null))
    }

    val client = {
      val _client = clientFactory(getConfig())
      Try {
        _client.endpoints().list().getItems()
        _client
      }.recover {
        case ex: Throwable =>
          // WORKAROUND for:
          // https://github.com/fabric8io/kubernetes-client/issues/2112#issue-594548439
          // not implemented yet:
          // https://github.com/fabric8io/kubernetes-client/issues/2612#issuecomment-748809432
          Try {
            logger.warn("Workaround to refresh credentials leveraging `kubectl`", ex)
            val processIo = new ProcessIO(_ => (), _ => (), _ => ())
            Process(Seq("kubectl", "get", "pods")).run(processIo).exitValue()
            _client.close()
          }
          clientFactory(getConfig())
      }
    }

    client
  }.flatten

  private def withClient[T](fn: KubernetesClient => Try[T]): Try[T] = {
    for {
      client <- kubeClient
      res <- fn(client)
    } yield {
      res
    }
  }

  private def getCrd(name: String, client: KubernetesClient) = {
    client
      .apiextensions()
      .v1beta1()
      .customResourceDefinitions()
      .inAnyNamespace()
      .list()
      .getItems()
      .asScala
      .find { crd =>
        val crdName = crd.getMetadata.getName
        logger.trace(s"Scanning Custom Resources found: ${name}")
        crdName == name
      }
  }

  private def getCloudflowApplicationsClient(client: KubernetesClient) =
    Try {
      val cloudflowClient = {
        client.customResources(App.customResourceDefinitionContext, classOf[App.Cr], classOf[App.List])
      }

      cloudflowClient
    }.recoverWith {
      case ex =>
        Failure(CliException("Cannot find cloudflow", ex))
    }

  private def getCrdAppVersion(name: String, client: KubernetesClient) =
    Try {
      val crd =
        getCrd(name, client)
          .getOrElse(throw CliException("Application resource not found in the cluster"))

      crd.getSpec.getVersion
    }.recoverWith {
      case ex =>
        Failure(CliException("Cannot find spark application", ex))
    }

  private lazy val cloudflowApplicationsClient: Try[MixedOperation[App.Cr, App.List, Resource[App.Cr]]] = {
    for {
      client <- kubeClient
      cloudflowAppClient <- getCloudflowApplicationsClient(client)
    } yield {
      cloudflowAppClient
    }
  }

  private def withApplicationClient[T](fn: MixedOperation[App.Cr, App.List, Resource[App.Cr]] => Try[T]): Try[T] = {
    for {
      client <- cloudflowApplicationsClient
      res <- fn(client)
    } yield {
      res
    }
  }

  import ModelConversions._

  def listCloudflowApps() = withApplicationClient { cloudflowApps =>
    logger.trace("Running the Fabric8 list command")
    Try {
      val res = cloudflowApps
        .inAnyNamespace()
        .list()
        .getItems
        .asScala
        .map(getCRSummary)
        .toList
      logger.trace(s"Fabric8 list command successful")
      res
    }
  }

  def getCloudflowAppStatus(appName: String): Try[models.ApplicationStatus] = withApplicationClient { cloudflowApps =>
    Try {
      val app = cloudflowApps
        .inAnyNamespace()
        .list()
        .getItems()
        .asScala
        .find(_.getMetadata.getName == appName)
        .getOrElse(throw CliException(s"""Cloudflow application "${appName}" not found"""))

      val appStatus: String = Try(app.status.appStatus).toOption.getOrElse("Unknown")

      val res = models.ApplicationStatus(
        summary = getCRSummary(app),
        status = appStatus,
        endpointsStatuses = Try(app.status.endpointStatuses).toOption
          .filterNot(_ == null)
          .map(_.map(getEndpointStatus))
          .getOrElse(Seq.empty),
        streamletsStatuses = Try(app.status.streamletStatuses).toOption
          .filterNot(_ == null)
          .map(_.map(getStreamletStatus))
          .getOrElse(Seq.empty))
      logger.trace(s"Fabric8 status command successful")
      res
    }
  }

  def getOperatorProtocolVersion(): Try[String] = withClient { client =>
    for {
      protocolVersionCM <- Try {
        client
          .configMaps()
          .inAnyNamespace()
          .withLabel(KubeClient.CloudflowProtocolVersionConfigMap)
          .list()
          .getItems()
      }
      protocolVersion <- {
        protocolVersionCM.size() match {
          case 1 => Success(protocolVersionCM.get(0))
          case x if x > 1 =>
            Failure(
              CliException("Multiple Cloudflow operators detected in the cluster. This is not supported. Exiting"))
          case x if x < 1 => Failure(CliException("No Cloudflow operators detected in the cluster. Exiting"))
        }
      }
      version <- Option(protocolVersion.getData.get(KubeClient.ProtocolVersionKey))
        .fold[Try[String]](Failure(CliException("Cannot find the protocol version in the config map")))(Success(_))
    } yield {
      version
    }
  }

  def sparkAppVersion() = withClient { client =>
    getCrdAppVersion(SparkResource, client)
  }

  def flinkAppVersion() = withClient { client =>
    getCrdAppVersion(FlinkResource, client)
  }

  private def cloudflowLabels(name: String) = {
    Map(
      "app.kubernetes.io/part-of" -> name,
      "app.kubernetes.io/managed-by" -> "cloudflow",
      "com.lightbend.cloudflow/app-id" -> name).asJava
  }

  def createNamespace(name: String): Try[Unit] =
    withClient { client =>
      val exists = client.namespaces().list().getItems.asScala.find { ns => ns.getMetadata.getName == name }

      if (exists.isEmpty) {
        Try {
          val namespace = new NamespaceBuilder()
            .withNewMetadata()
            .withName(name)
            .withLabels(cloudflowLabels(name))
            .endMetadata()
            .build()

          client.namespaces().create(namespace)
          ()
        }.recoverWith {
          case ex: Throwable =>
            Failure(CliException(s"Failed to create namespace ${name}", ex))
        }
      } else {
        Success(())
      }
    }

  def createImagePullSecret(
      namespace: String,
      dockerRegistryURL: String,
      dockerUsername: String,
      dockerPassword: String): Try[Unit] = withClient { client =>
    Try {
      val dockerConfigSecret = ".dockerconfigjson"

      val configEntry = DockerConfigEntry(
        username = dockerUsername,
        password = dockerPassword,
        auth = Base64Helper.encode(s"${dockerUsername}:${dockerPassword}"))

      def secret(config: String) =
        new SecretBuilder().withNewMetadata
          .withName(KubeClient.ImagePullSecretName)
          .withLabels(cloudflowLabels(namespace))
          .endMetadata
          .withType("kubernetes.io/dockerconfigjson")
          .addToData(dockerConfigSecret, Base64Helper.encode(config))
          .build()

      val prevImagePullSecret = client
        .secrets()
        .inNamespace(namespace)
        .list()
        .getItems
        .asScala
        .find(_.getMetadata.getName == KubeClient.ImagePullSecretName)

      prevImagePullSecret match {
        case None =>
          val config = DockerConfig(auths = Map(dockerRegistryURL -> configEntry))

          client
            .secrets()
            .inNamespace(namespace)
            .create(secret(Serialization.jsonMapper().writeValueAsString(config)))
        case Some(prev) =>
          val data = prev
            .getData()
            .asScala
            .get(dockerConfigSecret)
            .getOrElse(throw CliException("Failed to deserialize existing docker image pull secret"))
          val prevConfig =
            Serialization.jsonMapper().readValue(Base64Helper.decode(data), classOf[DockerConfig])

          val newConfig = prevConfig.copy(auths = prevConfig.auths.updated(dockerRegistryURL, configEntry))

          client
            .secrets()
            .inNamespace(namespace)
            .createOrReplace(secret(Serialization.jsonMapper().writeValueAsString(newConfig)))
      }
    }
  }

  private val appInputSecretConfKey = "secret.conf"
  private def appInputSecretName(name: String) = s"config-${name}"

  private val loggingSecretConfKey = "logback.xml"

  private def getOwnerReference(name: String, uid: String) = {
    new OwnerReferenceBuilder()
      .withController(true)
      .withBlockOwnerDeletion(true)
      .withApiVersion(App.ApiVersion)
      .withKind(App.Kind)
      .withName(name)
      .withUid(uid)
      .build()
  }

  def getAppInputSecret(name: String): Try[String] = withClient { client =>
    Try {
      val data =
        client.secrets
          .inNamespace(name)
          .list()
          .getItems
          .asScala
          .find(_.getMetadata.getName == appInputSecretName(name))
          .getOrElse(throw CliException(s"There is no configuration secret for ${name}, the application is corrupted"))
          .getData
          .get(appInputSecretConfKey)

      Base64Helper.decode(data)
    }
  }

  def createAppInputSecret(name: String, appConfig: String, ownerReference: OwnerReference) = withClient { client =>
    Try {
      lazy val secret =
        new SecretBuilder().withNewMetadata
          .withName(appInputSecretName(name))
          .withLabels(
            (cloudflowLabels(name).asScala ++
            Map(
              "com.lightbend.cloudflow/created-at" -> System.currentTimeMillis().toString,
              "com.lightbend.cloudflow/config-format" -> "input")).asJava)
          .withOwnerReferences(ownerReference)
          .endMetadata
          .addToStringData(appInputSecretConfKey, appConfig)
          .build()

      client.secrets
        .inNamespace(name)
        .withName(appInputSecretName(name))
        .createOrReplace(secret)
    }
  }

  private def createStreamletSecret(
      ownerReference: OwnerReference,
      name: String,
      secretName: String,
      streamletName: String,
      configs: Map[String, String]): Try[Unit] = {
    withClient { client =>
      Try {
        lazy val secret =
          new SecretBuilder().withNewMetadata
            .withName(secretName)
            .withLabels((cloudflowLabels(name).asScala ++
            Map(
              "com.lightbend.cloudflow/created-at" -> System.currentTimeMillis().toString,
              "com.lightbend.cloudflow/streamlet-name" -> streamletName,
              "com.lightbend.cloudflow/config-format" -> "config")).asJava)
            .withOwnerReferences(ownerReference)
            .endMetadata
            .addToStringData(configs.asJava)
            .build()

        client.secrets
          .inNamespace(name)
          .withName(secretName)
          .createOrReplace(secret)
      }
    }
  }

  private def createStreamletsConfigSecrets(
      name: String,
      secrets: Map[App.Deployment, Map[String, String]],
      ownerReference: OwnerReference): Try[Unit] = {
    secrets.foldLeft[Try[Unit]](Success(())) {
      case (last, (deployment, configs)) =>
        last match {
          case fail: Failure[_] => fail // Stop on first failure
          case _ =>
            createStreamletSecret(
              ownerReference = ownerReference,
              name = name,
              secretName = deployment.secretName,
              streamletName = deployment.streamletName,
              configs = configs)
        }
    }
  }

  def handleLoggingSecret(name: String, content: Option[String], ownerReference: OwnerReference) = withClient {
    client =>
      Try {
        content match {
          case None =>
            val current = client
              .secrets()
              .inNamespace(name)
              .withName(LoggingSecretName)
              .get()

            if (current != null) {
              client
                .secrets()
                .inNamespace(name)
                .withName(LoggingSecretName)
                .delete()
            }
          case Some(v) =>
            lazy val secret =
              new SecretBuilder().withNewMetadata
                .withName(LoggingSecretName)
                .withLabels(
                  (cloudflowLabels(name).asScala ++
                  Map(
                    "com.lightbend.cloudflow/created-at" -> System.currentTimeMillis().toString,
                    "com.lightbend.cloudflow/config-format" -> "input")).asJava)
                .withOwnerReferences(ownerReference)
                .endMetadata
                .addToStringData(loggingSecretConfKey, v)
                .build()

            client
              .secrets()
              .inNamespace(name)
              .withName(LoggingSecretName)
              .createOrReplace(secret)
        }
      }
  }

  private val CreatedByCliAnnotation = {
    Map("com.lightbend.cloudflow/created-by-cli-version" -> BuildInfo.version).asJava
  }

  private def createCloudflowServiceAccount(appId: String, ownerReference: OwnerReference) = withClient { client =>
    Try {

      val imagePullSecret = new LocalObjectReferenceBuilder()
        .withName(ImagePullSecretName)
        .build()

      val serviceAccount = new ServiceAccountBuilder()
        .withNewMetadata()
        .withName(CloudflowAppServiceAccountName)
        .withNamespace(appId)
        .withLabels(cloudflowLabels(appId))
        .withOwnerReferences(ownerReference)
        .endMetadata()
        .withImagePullSecrets(imagePullSecret)
        .withAutomountServiceAccountToken(true)
        .build()

      client
        .serviceAccounts()
        .inNamespace(appId)
        .createOrReplace(serviceAccount)
    }
  }

  private def getFullCloudflowApp(spec: App.Spec): App.Cr = {
    val metadata = new ObjectMetaBuilder()
      .withName(spec.appId)
      .withLabels(cloudflowLabels(spec.appId))
      .withAnnotations(CreatedByCliAnnotation)
      .build()

    val status = App.AppStatus(
      appId = spec.appId,
      appVersion = spec.appVersion,
      appMessage = "",
      appStatus = "",
      endpointStatuses = Seq(),
      streamletStatuses = Seq())

    App.Cr(spec = spec, metadata = metadata, status = status)
  }

  private def createCFApp(spec: App.Spec): Try[String] =
    withApplicationClient { cloudflowApps =>
      for {
        uid <- Try {
          val app = getFullCloudflowApp(spec)

          val crd =
            cloudflowApps
              .inNamespace(spec.appId)
              .withName(spec.appId)
              .createOrReplace(app)

          crd.getMetadata.getUid
        }
      } yield {
        uid
      }
    }

  def createCloudflowApp(spec: App.Spec): Try[String] =
    for {
      uid <- createCFApp(spec)
      _ <- createCloudflowServiceAccount(spec.appId, getOwnerReference(spec.appId, uid))
    } yield { uid }

  def createMicroservicesApp(cfSpec: App.Spec, specs: Map[String, Option[AkkaMicroserviceSpec]]): Try[String] = {
    for {
      uid <- createCFApp(cfSpec)
      _ <- {
        withClient { client =>
          val microservices = client.customResources(
            AkkaMicroservice.customResourceDefinitionContext,
            classOf[AkkaMicroservice],
            classOf[AkkaMicroserviceList])

          specs.foldLeft(Success(Map.empty[String, String]): Try[Map[String, String]]) {
            case (last, (name, spec)) =>
              last match {
                case f: Failure[Map[String, String]] => f
                case Success(l) =>
                  spec match {
                    case None => Success(l + (name -> uid))
                    case Some(s) =>
                      val metadata = new ObjectMetaBuilder()
                        .withName(name)
                        .withLabels(cloudflowLabels(name))
                        .withAnnotations(CreatedByCliAnnotation)
                        .withOwnerReferences(getOwnerReference(cfSpec.appId, uid))
                        .build()

                      val app = AkkaMicroservice(spec = s, metadata = metadata, status = None)

                      val crd =
                        microservices
                          .inNamespace(cfSpec.appId)
                          .withName(name)
                          .createOrReplace(app)

                      Success(l + (name -> crd.getMetadata.getUid))
                  }
              }
          }
        }
      }
    } yield { uid }
  }

  def uidCloudflowApp(name: String): Try[String] = {
    withApplicationClient { cloudflowApps =>
      Try {
        val current = cloudflowApps
          .inNamespace(name)
          .withName(name)
          .get()

        if (current == null) {
          throw new CliException(s"Cloudflow application ${name} not found")
        }

        current.getMetadata.getUid
      }
    }
  }

  def configureCloudflowApp(
      appName: String,
      appUid: String,
      appConfig: String,
      loggingContent: Option[String],
      configs: Map[App.Deployment, Map[String, String]]) = {
    val ownerReference = getOwnerReference(appName, appUid)
    for {
      _ <- handleLoggingSecret(appName, loggingContent, ownerReference)
      _ <- createStreamletsConfigSecrets(appName, configs, ownerReference)
      _ <- createAppInputSecret(appName, appConfig, ownerReference)
    } yield { () }
  }

  def updateCloudflowApp(app: App.Cr): Try[App.Cr] = withApplicationClient { cloudflowApps =>
    Try {
      cloudflowApps
        .inNamespace(app.spec.appId)
        .withName(app.spec.appId)
        // NOTE: Patch doesn't work
        //.patch(app)
        .replace(app)
    }
  }

  def deleteCloudflowApp(appName: String): Try[Unit] = withApplicationClient { cloudflowApps =>
    Try {
      val app = cloudflowApps
        .inAnyNamespace()
        .list()
        .getItems()
        .asScala
        .find(_.getMetadata.getName == appName)
        .getOrElse(throw CliException(s"""Cloudflow application "${appName}" not found"""))

      cloudflowApps
        .inAnyNamespace()
        .delete(app)
    }
  }

  def getPvcs(namespace: String): Try[List[String]] = withClient { client =>
    Try {
      client
        .persistentVolumeClaims()
        .inNamespace(namespace)
        .list()
        .getItems
        .asScala
        .map { _.getMetadata.getName }
        .toList
    }
  }

  private val SecretDataKey = "secret.conf"

  def getKafkaClusters(namespace: Option[String]): Try[Map[String, String]] = withClient { client =>
    Try {
      val secrets = {
        namespace match {
          case Some(ns) =>
            client.secrets().inNamespace(ns)
          case _ =>
            client.secrets().inAnyNamespace()
        }
      }

      secrets
        .withLabel(KafkaClusterNameLabel)
        .list()
        .getItems
        .asScala
        .map { secret =>
          (Option(secret.getMetadata.getLabels), secret.getData.asScala.get(SecretDataKey)) match {
            case (Some(labels), Some(config)) =>
              Some(labels.asScala(KafkaClusterNameLabel) -> Base64Helper.decode(config))
            case _ => None
          }
        }
        .flatten
        .toMap
    }
  }

  def readCloudflowApp(name: String): Try[Option[App.Cr]] = withApplicationClient { cloudflowApps =>
    Try {
      Option(
        cloudflowApps
          .inNamespace(name)
          .withName(name)
          .get())
    }
  }
}

private object ModelConversions {

  def getCRSummary(app: App.Cr): models.CRSummary = {
    models.CRSummary(
      name = app.name,
      namespace = app.namespace,
      version = app.spec.appVersion,
      creationTime = app.getMetadata.getCreationTimestamp)
  }

  def getEndpointStatus(status: App.EndpointStatus): models.EndpointStatus = {
    models.EndpointStatus(name = status.streamletName, url = status.url)
  }

  def getPodStatus(status: App.PodStatus): models.PodStatus = {
    models.PodStatus(
      name = status.name,
      ready = models
        .ContainersReady(status.nrOfContainersReady, status.nrOfContainers),
      status = status.status,
      restarts = status.restarts)
  }

  def getStreamletStatus(status: App.StreamletStatus): models.StreamletStatus = {
    models.StreamletStatus(name = status.streamletName, podsStatuses = status.podStatuses.map(getPodStatus))
  }
}

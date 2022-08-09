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
        _client.secrets().list().getItems()
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
  }.recover {
    case ex: Throwable =>
      logger.error("Failed to create the kubernetes client", ex)
      throw ex
  }.flatten

  private def withClient[T](fn: KubernetesClient => Try[T]): Try[T] = {
    for {
      client <- kubeClient
      res <- fn(client)
    } yield {
      res
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

  def listCloudflowApps(namespace: Option[String]) = withApplicationClient { cloudflowApps =>
    logger.trace("Running the Fabric8 list command")
    Try {
      val apps =
        namespace match {
          case Some(ns) => cloudflowApps.inNamespace(ns)
          case _        => cloudflowApps.inAnyNamespace()
        }
      val res = apps
        .list()
        .getItems
        .asScala
        .map(getCRSummary)
        .toList
      logger.trace(s"Fabric8 list command successful")
      res
    }
  }

  def getCloudflowAppStatus(appName: String, namespace: String): Try[models.ApplicationStatus] = withApplicationClient {
    cloudflowApps =>
      Try {
        val app = cloudflowApps
          .inNamespace(namespace)
          .list()
          .getItems()
          .asScala
          .find(_.getMetadata.getName == appName)
          .getOrElse(throw CliException(s"""Cloudflow application "${appName}" not found"""))

        val appStatus: String = Try(app.getStatus.appStatus).toOption.getOrElse("Unknown")

        val res = models.ApplicationStatus(
          summary = getCRSummary(app),
          status = appStatus,
          // FIXME, remove in a breaking CRD change, the endpoint statuses are not updated anymore.
          endpointsStatuses = Try(app.getStatus.endpointStatuses).toOption
            .filterNot(_ == null)
            .map(_.map(getEndpointStatus))
            .getOrElse(Seq.empty),
          streamletsStatuses = Try(app.getStatus.streamletStatuses).toOption
            .filterNot(_ == null)
            .map(_.map(getStreamletStatus))
            .getOrElse(Seq.empty))
        logger.trace(s"Fabric8 status command successful")
        res
      }
  }

  def getOperatorProtocolVersion(namespace: Option[String]): Try[String] = withClient { client =>
    val secrets = namespace match {
      case Some(ns) => client.secrets().inNamespace(ns)
      case _        => client.secrets().inAnyNamespace()
    }

    for {
      protocolVersionSecret <- Try {
        secrets
          .withLabel(App.CloudflowProtocolVersion)
          .list()
          .getItems()
      }
      protocolVersion <- {
        protocolVersionSecret.size() match {
          case 1 => Success(protocolVersionSecret.get(0))
          case x if x > 1 =>
            Failure(CliException(
              "Multiple Cloudflow operators detected in the cluster. Specify an 'operator-namespace' to select the correct one. Exiting"))
          case x if x < 1 => Failure(CliException("No Cloudflow operators detected in the cluster. Exiting"))
        }
      }
      version <- Option(Base64Helper.decode(protocolVersion.getData.get(App.ProtocolVersionKey)))
        .fold[Try[String]](Failure(CliException("Cannot find the protocol version in the secret")))(Success(_))
    } yield {
      version
    }
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
          .withNamespace(namespace)
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

  def getAppInputSecret(name: String, namespace: String): Try[String] = withClient { client =>
    Try {
      val data =
        client.secrets
          .inNamespace(namespace)
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

  def createAppInputSecret(name: String, namespace: String, appConfig: String, ownerReference: OwnerReference) =
    withClient { client =>
      Try {
        lazy val secret =
          new SecretBuilder().withNewMetadata
            .withName(appInputSecretName(name))
            .withNamespace(namespace)
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
          .inNamespace(namespace)
          .withName(appInputSecretName(name))
          .createOrReplace(secret)
      }
    }

  private def createStreamletSecret(
      ownerReference: OwnerReference,
      name: String,
      namespace: String,
      secretName: String,
      streamletName: String,
      configs: Map[String, String]): Try[Unit] = {
    withClient { client =>
      Try {
        lazy val secret =
          new SecretBuilder().withNewMetadata
            .withName(secretName)
            .withNamespace(namespace)
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
          .inNamespace(namespace)
          .withName(secretName)
          .createOrReplace(secret)
      }
    }
  }

  private def createStreamletsConfigSecrets(
      name: String,
      namespace: String,
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
              namespace = namespace,
              secretName = deployment.secretName,
              streamletName = deployment.streamletName,
              configs = configs)
        }
    }
  }

  def handleLoggingSecret(name: String, namespace: String, content: Option[String], ownerReference: OwnerReference) =
    withClient { client =>
      Try {
        content match {
          case None =>
            val current = client
              .secrets()
              .inNamespace(namespace)
              .withName(LoggingSecretName)
              .get()

            if (current != null) {
              client
                .secrets()
                .inNamespace(namespace)
                .withName(LoggingSecretName)
                .delete()
            }
          case Some(v) =>
            lazy val secret =
              new SecretBuilder().withNewMetadata
                .withName(LoggingSecretName)
                .withNamespace(namespace)
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
              .inNamespace(namespace)
              .withName(LoggingSecretName)
              .createOrReplace(secret)
        }
      }
    }

  private val CreatedByCliAnnotation = {
    Map("com.lightbend.cloudflow/created-by-cli-version" -> BuildInfo.version).asJava
  }

  private def createCloudflowServiceAccount(appId: String, namespace: String, ownerReference: OwnerReference) =
    withClient { client =>
      Try {

        val imagePullSecret = new LocalObjectReferenceBuilder()
          .withName(ImagePullSecretName)
          .build()

        val serviceAccount = new ServiceAccountBuilder()
          .withNewMetadata()
          .withName(CloudflowAppServiceAccountName)
          .withNamespace(namespace)
          .withLabels(cloudflowLabels(appId))
          .withOwnerReferences(ownerReference)
          .endMetadata()
          .withImagePullSecrets(imagePullSecret)
          .withAutomountServiceAccountToken(true)
          .build()

        client
          .serviceAccounts()
          .inNamespace(namespace)
          .createOrReplace(serviceAccount)
      }
    }

  private def getFullCloudflowApp(spec: App.Spec, namespace: String): App.Cr = {
    val metadata = new ObjectMetaBuilder()
      .withName(spec.appId)
      .withNamespace(namespace)
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

    App.Cr(_spec = spec, _metadata = metadata, _status = status)
  }

  private def createCFApp(spec: App.Spec, namespace: String): Try[String] =
    withApplicationClient { cloudflowApps =>
      for {
        uid <- Try {
          val app = getFullCloudflowApp(spec, namespace)

          val crd =
            cloudflowApps
              .inNamespace(namespace)
              .withName(spec.appId)
              .createOrReplace(app)

          crd.getMetadata.getUid
        }
      } yield {
        uid
      }
    }

  def createCloudflowApp(spec: App.Spec, namespace: String): Try[String] =
    for {
      uid <- createCFApp(spec, namespace)
      _ <- {
        if (spec.serviceAccount.isEmpty)
          createCloudflowServiceAccount(spec.appId, namespace, getOwnerReference(spec.appId, uid))
        else Success(())
      }
    } yield { uid }

  def uidCloudflowApp(name: String, namespace: String): Try[String] = {
    withApplicationClient { cloudflowApps =>
      Try {
        val current = cloudflowApps
          .inNamespace(namespace)
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
      namespace: String,
      appUid: String,
      appConfig: String,
      loggingContent: Option[String],
      configs: Map[App.Deployment, Map[String, String]]) = {
    val ownerReference = getOwnerReference(appName, appUid)
    for {
      _ <- handleLoggingSecret(appName, namespace, loggingContent, ownerReference)
      _ <- createStreamletsConfigSecrets(appName, namespace, configs, ownerReference)
      _ <- createAppInputSecret(appName, namespace, appConfig, ownerReference)
    } yield { () }
  }

  def updateCloudflowApp(app: App.Cr, namespace: String): Try[App.Cr] = withApplicationClient { cloudflowApps =>
    Try {
      cloudflowApps
        .inNamespace(namespace)
        .withName(app.getSpec.appId)
        // NOTE: Patch doesn't work
        //.patch(app)
        .replace(app)
    }
  }

  def deleteCloudflowApp(appName: String, namespace: String): Try[Unit] = withApplicationClient { cloudflowApps =>
    Try {
      val app = cloudflowApps
        .inNamespace(namespace)
        .list()
        .getItems()
        .asScala
        .find(_.getMetadata.getName == appName)
        .getOrElse(throw CliException(s"""Cloudflow application "${appName}" not found"""))

      cloudflowApps
        .inNamespace(namespace)
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

  def readCloudflowApp(name: String, namespace: String): Try[Option[App.Cr]] = withApplicationClient { cloudflowApps =>
    Try {
      Option(
        cloudflowApps
          .inNamespace(namespace)
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
      version = app.getSpec.appVersion,
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

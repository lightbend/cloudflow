/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.microservice

import com.fasterxml.jackson.annotation.JsonInclude.Include

import scala.collection.immutable
import com.fasterxml.jackson.annotation.{ JsonCreator, JsonInclude, JsonProperty }
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.api.model.apps.DeploymentStrategy
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.client.CustomResourceList
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext
import io.fabric8.kubernetes.client.utils.Serialization
import io.fabric8.kubernetes.model.annotation.Group
import io.fabric8.kubernetes.model.annotation.Kind
import io.fabric8.kubernetes.model.annotation.Plural
import io.fabric8.kubernetes.model.annotation.Version

object AkkaMicroservice {
  final val Kind = "AkkaMicroservice"
  final val Plural = "akkamicroservices"
  final val Group = "akka.lightbend.com"
  final val Version = "v1"
  val ApiVersion: String = Group + "/" + Version

  val finalName: String = Plural + "." + Group

  val customResourceDefinitionContext: CustomResourceDefinitionContext =
    new CustomResourceDefinitionContext.Builder()
      .withVersion(Version)
      .withKind(Kind)
      .withGroup(Group)
      .withPlural(Plural)
      .withScope("Namespaced")
      .build()
}

@JsonCreator
@Group(AkkaMicroservice.Group)
@Version(AkkaMicroservice.Version)
@Kind(AkkaMicroservice.Kind)
@Plural(AkkaMicroservice.Plural)
final case class AkkaMicroservice(
    kind: String = AkkaMicroservice.Kind,
    apiVersion: String = AkkaMicroservice.ApiVersion,
    metadata: ObjectMeta,
    spec: AkkaMicroserviceSpec,
    status: Option[AkkaMicroserviceStatus])
    extends CustomResource
    with Namespaced {
  setMetadata(metadata)
}

@JsonCreator
final case class AkkaMicroserviceList(
    kind: String,
    apiVersion: String,
    metadata: ListMeta,
    items: List[AkkaMicroservice])
    extends CustomResourceList[AkkaMicroservice] {
  import scala.jdk.CollectionConverters._
  setKind(kind)
  setApiVersion(apiVersion)
  setMetadata(metadata)
  setItems(items.asJava)
}

@JsonDeserialize(using = classOf[JsonDeserializer.None])
// NOTE: remember to have this annotation in this codebase!
@JsonInclude(Include.NON_ABSENT)
@JsonCreator
final case class AkkaMicroserviceSpec(
    replicas: Int,
    image: String,
    imagePullPolicy: Option[String],
    appVersion: Option[String],
    imagePullSecrets: immutable.Seq[String],
    readinessProbe: Option[JsonNode],
    livenessProbe: Option[JsonNode],
    // configSecret: Option[ConfigSecret],
    secretVolumes: immutable.Seq[SecretVolume],
    javaOptions: String,
    logbackSecret: Option[LogbackSecret],
    extraVolumeMounts: immutable.Seq[JsonNode],
    podTemplateSpec: Option[JsonNode])
    extends KubernetesResource {}

@JsonDeserialize(using = classOf[JsonDeserializer.None])
@JsonCreator
final case class ConfigSecret(secretName: String) extends KubernetesResource

@JsonDeserialize(using = classOf[JsonDeserializer.None])
@JsonCreator
final case class LogbackSecret(secretName: String) extends KubernetesResource

@JsonDeserialize(using = classOf[JsonDeserializer.None])
@JsonCreator
final case class SecretVolume(secretName: String, mountPath: String) extends KubernetesResource

@JsonDeserialize(using = classOf[JsonDeserializer.None])
@JsonCreator
final case class AkkaMicroserviceStatus(
    phase: String,
    availableReplicas: Int,
    message: String,
    podStatus: List[String],
    oldestPodStartTime: String,
    akkaClusterStatus: Option[AkkaClusterStatus])
    extends KubernetesResource

@JsonDeserialize(using = classOf[JsonDeserializer.None])
@JsonCreator
final case class AkkaClusterStatus(upMembers: Int, nonUpMembers: Int, unreachableMembers: Int, lastChangeTime: String) {}

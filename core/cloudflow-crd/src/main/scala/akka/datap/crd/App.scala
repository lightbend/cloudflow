/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.datap.crd

import java.net.URL
import scala.collection.immutable
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.fabric8.kubernetes.api.model.apiextensions.v1.{
  CustomResourceDefinitionBuilder,
  CustomResourceDefinitionVersion,
  CustomResourceDefinitionVersionBuilder
}
import io.fabric8.kubernetes.api.model.{ KubernetesResource, Namespaced, ObjectMeta }
import io.fabric8.kubernetes.client.{ CustomResource, CustomResourceList }
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext
import io.fabric8.kubernetes.model.annotation.{ Group, Kind, Plural, Version }

object App {

  // GroupName for our CR
  final val GroupName = "cloudflow.lightbend.com"

  // GroupVersion for our CR
  final val GroupVersion = "v1alpha1"

  // Kind for our CR
  final val Kind = "CloudflowApplication"

  final val KindList = s"${Kind}List"

  final val Singular = "cloudflowapplication"

  final val Plural = "cloudflowapplications"

  final val Short = "cloudflowapp"

  final val ApiVersion = GroupName + "/" + GroupVersion

  final val ResourceName = s"$Plural.$GroupName"

  final val Scope = "Namespaced"

  final val CloudflowProtocolVersion = "cloudflow-protocol-version"
  final val ProtocolVersionKey = "protocol-version"
  final val ProtocolVersion = "7"

  val customResourceDefinitionVersion: CustomResourceDefinitionVersion = {
    new CustomResourceDefinitionVersionBuilder()
      .withName(GroupVersion)
      .withNewSubresources()
      .withNewStatus()
      .endStatus()
      .endSubresources()
      .build()
  }
  val customResourceDefinitionContext: CustomResourceDefinitionContext =
    new CustomResourceDefinitionContext.Builder()
      .withVersion(GroupVersion)
      .withKind(Kind)
      .withGroup(GroupName)
      .withPlural(Plural)
      .withScope(Scope)
      .build()

  val Crd =
    new CustomResourceDefinitionBuilder()
      .withNewMetadata()
      .withName(ResourceName)
      .endMetadata()
      .withNewSpec()
      .withGroup(GroupName)
      .withNewNames()
      .withNewKind(Kind)
      .withListKind(KindList)
      .withSingular(Singular)
      .withPlural(Plural)
      .withShortNames(Short)
      .endNames()
      .withVersions(customResourceDefinitionVersion)
      .withScope("Namespaced")
      .withPreserveUnknownFields(true)
      .endSpec()
      .withNewStatus()
      .withStoredVersions(GroupVersion)
      .endStatus()
      .build()

  @JsonCreator
  @Group(GroupName)
  @Version(GroupVersion)
  @Kind(Kind)
  @Plural(Plural)
  final case class Cr(
      _spec: Spec,
      @JsonProperty("metadata")
      _metadata: ObjectMeta,
      _status: AppStatus = null)
      extends CustomResource[Spec, AppStatus]
      with Namespaced {
    this.setMetadata(_metadata)
    this.setSpec(_spec)
    this.setStatus(_status)
    override def initSpec = _spec
    override def initStatus = _status
    def name: String = getMetadata.getName()
    def namespace: String = getMetadata.getNamespace()
  }

  @JsonCreator
  class List extends CustomResourceList[Cr] {}

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class Attribute(
      @JsonProperty("attribute_name")
      attributeName: String,
      @JsonProperty("config_path")
      configPath: String)
      extends KubernetesResource {}

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class InOutletSchema(
      @JsonProperty("fingerprint")
      fingerprint: String,
      @JsonProperty("schema")
      schema: String,
      @JsonProperty("name")
      name: String,
      @JsonProperty("format")
      format: String)
      extends KubernetesResource {}

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class InOutlet(
      @JsonProperty("name")
      name: String,
      @JsonProperty("schema")
      schema: InOutletSchema)
      extends KubernetesResource {}

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class ConfigParameterDescriptor(
      @JsonProperty("key")
      key: String,
      @JsonProperty("description")
      description: String,
      @JsonProperty("validation_type")
      validationType: String,
      @JsonProperty("validation_pattern")
      validationPattern: String = "",
      @JsonProperty("default_value")
      defaultValue: String)
      extends KubernetesResource {}

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class Descriptor(
      @JsonProperty("attributes")
      attributes: immutable.Seq[Attribute],
      @JsonProperty("class_name")
      className: String,
      @JsonProperty("config_parameters")
      configParameters: immutable.Seq[ConfigParameterDescriptor],
      @JsonProperty("volume_mounts")
      volumeMounts: immutable.Seq[VolumeMountDescriptor],
      @JsonProperty("inlets")
      inlets: immutable.Seq[InOutlet],
      @JsonProperty("labels")
      labels: immutable.Seq[String],
      @JsonProperty("outlets")
      outlets: immutable.Seq[InOutlet],
      @JsonProperty("runtime")
      runtime: String,
      @JsonProperty("description")
      description: String)
      extends KubernetesResource {}
  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class Streamlet(
      @JsonProperty("name")
      name: String,
      @JsonProperty("descriptor")
      descriptor: Descriptor)
      extends KubernetesResource {}

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class Endpoint(
      @JsonProperty("app_id")
      appId: Option[String],
      @JsonProperty("streamlet")
      streamlet: Option[String],
      @JsonProperty("container_port")
      containerPort: Option[Int])
      extends KubernetesResource {}

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class VolumeMountDescriptor(
      @JsonProperty("name")
      name: String,
      @JsonProperty("path")
      path: String,
      @JsonProperty("access_mode")
      accessMode: String,
      @JsonProperty("pvc_name")
      pvcName: Option[String])
      extends KubernetesResource {}

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class PortMapping(
      @JsonProperty("id")
      id: String,
      @JsonProperty("config")
      config: JsonNode,
      @JsonInclude(value = Include.NON_EMPTY, content = Include.NON_NULL)
      @JsonProperty("cluster")
      cluster: Option[String])
      extends KubernetesResource {}

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class Deployment(
      @JsonProperty("class_name")
      className: String,
      @JsonProperty("config")
      config: JsonNode,
      @JsonProperty("image")
      image: String,
      @JsonProperty("name")
      name: String,
      @JsonProperty("port_mappings")
      portMappings: Map[String, PortMapping] = Map(),
      @JsonProperty("volume_mounts")
      volumeMounts: immutable.Seq[VolumeMountDescriptor] = immutable.Seq(),
      @JsonProperty("runtime")
      runtime: String,
      @JsonProperty("streamlet_name")
      streamletName: String,
      @JsonProperty("secret_name")
      secretName: String,
      @JsonInclude(value = Include.NON_EMPTY, content = Include.NON_NULL)
      @JsonProperty("endpoint")
      endpoint: Option[Endpoint],
      @JsonInclude(value = Include.NON_EMPTY, content = Include.NON_NULL)
      @JsonProperty("replicas")
      replicas: Option[Int])
      extends KubernetesResource {}

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class Spec(
      @JsonProperty("app_id")
      appId: String,
      @JsonProperty("app_version")
      appVersion: String,
      @JsonProperty("deployments")
      deployments: immutable.Seq[Deployment],
      @JsonProperty("streamlets")
      streamlets: immutable.Seq[Streamlet],
      @JsonProperty("agent_paths")
      agentPaths: Map[String, String],
      @JsonProperty("version")
      version: Option[String],
      @JsonProperty("library_version")
      libraryVersion: Option[String],
      @JsonProperty("service_account")
      serviceAccount: Option[String])
      extends KubernetesResource {}

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class EndpointStatus(
      @JsonProperty("streamlet_name")
      streamletName: String,
      @JsonProperty("url")
      url: URL // check if works...
  ) extends KubernetesResource {}

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class PodStatus(
      @JsonProperty("name")
      name: String,
      @JsonProperty("ready")
      ready: String,
      @JsonProperty("nr_of_containers_ready")
      nrOfContainersReady: Int,
      @JsonProperty("nr_of_containers")
      nrOfContainers: Int,
      @JsonProperty("restarts")
      restarts: Int,
      @JsonProperty("status")
      status: String)
      extends KubernetesResource {}

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class StreamletStatus(
      @JsonProperty("streamlet_name")
      streamletName: String,
      @JsonProperty("expected_pod_count")
      expectedPodCount: Option[Int],
      @JsonProperty("pod_statuses")
      podStatuses: immutable.Seq[PodStatus])
      extends KubernetesResource {}

  @JsonDeserialize(using = classOf[JsonDeserializer.None])
  @JsonCreator
  final case class AppStatus(
      @JsonProperty("app_id")
      appId: String,
      @JsonProperty("app_version")
      appVersion: String,
      @JsonProperty("app_status")
      appStatus: String,
      @JsonProperty("app_message")
      appMessage: String,
      // FIXME not updated anymore, remove in a next CRD version.
      @JsonProperty("endpoint_statuses")
      endpointStatuses: immutable.Seq[EndpointStatus],
      @JsonProperty("streamlet_statuses")
      streamletStatuses: immutable.Seq[StreamletStatus])
      extends KubernetesResource {}

}

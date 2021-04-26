/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

// TODO: regenerate GraalVM config!
package akka.cloudflow.config

import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try }
import akka.datap.crd.App
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import pureconfig.configurable.{ genericMapReader, genericMapWriter }
import pureconfig.{
  ConfigCursor,
  ConfigFieldMapping,
  ConfigObjectCursor,
  ConfigObjectSource,
  ConfigReader,
  ConfigSource,
  ConfigWriter
}
import pureconfig.error.{ CannotConvert, ConfigReaderFailures, ExceptionThrown, FailureReason }
import pureconfig.generic.ProductHint
import pureconfig.module.magnolia.auto.reader.exportReader
import pureconfig.module.magnolia.auto.writer.exportWriter

// The order of the elements in this file matter, please make sure you go from leaf to nodes
object CloudflowConfig {

  // Quantity
  final case class Quantity(value: String)

  // from: https://github.com/kubernetes/apimachinery/blob/ae8b5f5092d37b75a20fef7531de129d21b9e0b5/pkg/api/resource/quantity.go#L45-L47
  // and: https://github.com/fabric8io/kubernetes-client/blob/1b4a4561542a98d75bf7f45cd203aae8a1db4e38/kubernetes-model-generator/kubernetes-model-core/src/main/java/io/fabric8/kubernetes/api/model/Quantity.java#L127-L173
  // TODO: remove after we bump to fabric8 that includes this fix: https://github.com/fabric8io/kubernetes-client/pull/2897
  private val validQuantityFormats =
    Seq("Ki", "Mi", "Gi", "Ti", "Pi", "Ei", "n", "u", "m", "k", "M", "G", "T", "P", "E", "", null)

  implicit val quantityReader = ConfigReader.fromCursor[Quantity] { cur =>
    cur.asString.flatMap { str =>
      Try {
        val q = io.fabric8.kubernetes.api.model.Quantity.parse(str)
        assert { validQuantityFormats.contains(q.getFormat) }
        io.fabric8.kubernetes.api.model.Quantity.getAmountInBytes(q)
      } match {
        case Success(v) if v != null => Right(Quantity(str))
        case _ =>
          cur.failed(CannotConvert(str, "Quantity", s"is not a valid Kubernetes quantity"))
      }
    }
  }

  implicit val quantityWriter: ConfigWriter[Quantity] = ConfigWriter.fromFunction[Quantity] { qty: Quantity =>
    ConfigValueFactory.fromAnyRef(io.fabric8.kubernetes.api.model.Quantity.parse(qty.value).toString())
  }

  // EnvVar
  final case class EnvVar(name: String, value: String)

  implicit val envVarHint = ProductHint[EnvVar](allowUnknownKeys = false)

  // Requirement
  final case class Requirement(memory: Option[Quantity] = None, cpu: Option[Quantity] = None)

  implicit val requirementHint = ProductHint[Requirement](allowUnknownKeys = false)

  // Volumes
  sealed trait Volume
  final case class SecretVolume(name: String) extends Volume
  final case class PvcVolume(name: String, readOnly: Boolean = true) extends Volume

  implicit val secretVolumeHint = ProductHint[SecretVolume](allowUnknownKeys = false)
  implicit val pvcVolumeHint = ProductHint[PvcVolume](allowUnknownKeys = false)

  private val secretReader = exportReader[SecretVolume].instance
  private val pvcReader = exportReader[PvcVolume].instance

  private def extractByType(typ: String, objCur: ConfigObjectCursor): ConfigReader.Result[Volume] = typ match {
    case "secret" => secretReader.from(objCur)
    case "pvc"    => pvcReader.from(objCur)
    case t =>
      objCur.failed(CannotConvert(t, "Volume", s"should be secret or pvc"))
  }

  implicit val volumeConfigReader: ConfigReader[Volume] = ConfigReader.fromCursor[Volume] { cur =>
    for {
      volume <- cur.asMap
      _ <- volume.size match {
        case i if i > 1 =>
          Left(
            ConfigReaderFailures(
              cur.failureFor(ExceptionThrown(ConfigException("volume has multiple definitions, only one is allowed")))))
        case i if i == 0 =>
          Left(
            ConfigReaderFailures(
              cur.failureFor(ExceptionThrown(ConfigException("volume doesn't have a parseable definition")))))
        case _ => Right(())
      }
      (k, v) = volume.head
      body <- v.asObjectCursor
      extracted <- extractByType(k, body)
    } yield {
      extracted
    }
  }

  private val pvcVolumeWriter = exportWriter[PvcVolume].instance
  private val secretVolumeWriter = exportWriter[SecretVolume].instance

  implicit val volumeConfigWriter: ConfigWriter[Volume] = ConfigWriter.fromFunction[Volume] { volume: Volume =>
    volume match {
      case pvc: PvcVolume =>
        ConfigFactory.parseMap(Map("pvc" -> pvcVolumeWriter.to(pvc)).asJava).root()
      case secret: SecretVolume =>
        ConfigFactory.parseMap(Map("secret" -> secretVolumeWriter.to(secret)).asJava).root()
    }
  }

  // Volume mount
  final case class VolumeMount(mountPath: String = "", readOnly: Boolean = true, subPath: String = "")

  implicit val volumeMountHint = ProductHint[VolumeMount](
    ConfigFieldMapping(Map("mountPath" -> "mount-path", "readOnly" -> "read-only", "subPath" -> "subPath")),
    useDefaultArgs = true,
    allowUnknownKeys = false)

  // ContainerPortName
  final case class ContainerPortName(value: String)

  // https://github.com/kubernetes/kubernetes/issues/50619#issuecomment-518220654
  private val containerPortNameFormat = """^[a-z0-9A-Z\-]*""".r
  private val oneLetterFormat = "^.*[a-zA-Z].*".r

  private def validatePortName(str: String) = {
    // MUST be at least 1 character and no more than 15 characters long
    if (str.size >= 1 && str.size <= 15) {
      // MUST contain only US-ASCII [ANSI.X3.4-1986] letters 'A' - 'Z' and
      // 'a' - 'z', digits '0' - '9', and hyphens ('-', ASCII 0x2D or
      // decimal 45)
      if (containerPortNameFormat.matches(str)) {
        // MUST contain at least one letter ('A' - 'Z' or 'a' - 'z')
        if (oneLetterFormat.matches(str)) {
          // MUST NOT begin or end with a hyphen
          if (!str.startsWith("-") && !str.endsWith("-")) {
            // hyphens MUST NOT be adjacent to other hyphens
            !str.contains("--")
          } else false
        } else false
      } else false
    } else false
  }

  implicit val containerPortNameReader = ConfigReader.fromCursor[ContainerPortName] { cur =>
    cur.asString.flatMap { str =>
      if (validatePortName(str)) {
        Right(ContainerPortName(str))
      } else {
        cur.failed(CannotConvert(str, "ContainerPortName", s"is not a valid Kubernetes portName"))
      }
    }
  }

  implicit val containerPortNameWriter: ConfigWriter[ContainerPortName] = ConfigWriter.fromFunction[ContainerPortName] {
    cpn: ContainerPortName => ConfigValueFactory.fromAnyRef(cpn.value)
  }

  // ContainerPort
  final case class ContainerPort(
      containerPort: Int,
      protocol: String = "TCP",
      name: Option[ContainerPortName] = None,
      hostIP: String = "",
      hostPort: Option[Int] = None)

  implicit val containerPortHint = ProductHint[ContainerPort](
    ConfigFieldMapping(
      Map(
        "containerPort" -> "container-port",
        "protocol" -> "protocol",
        "name" -> "name",
        "hostIP" -> "host-ip",
        "hostPort" -> "host-port")),
    useDefaultArgs = true,
    allowUnknownKeys = false)

  // Requirements
  final case class Requirements(requests: Requirement = Requirement(), limits: Requirement = Requirement())

  implicit val requirementsHint = ProductHint[Requirements](allowUnknownKeys = false)

  // Container
  final case class Container(
      env: Option[List[EnvVar]] = None,
      ports: Option[List[ContainerPort]] = None,
      resources: Requirements = Requirements(),
      volumeMounts: Map[String, VolumeMount] = Map())

  implicit val containerHint = ProductHint[Container](allowUnknownKeys = false)

  // LabelValue

  final case class LabelValue(value: String)

  private val labelValuePattern = """^[a-z0-9A-Z]{1}[a-z0-9A-Z\.\_\-]{0,61}[a-z0-9A-Z]{1}$""".r
  private val labelValueSingleCharFormat = "^[a-z0-9A-Z]{1}$".r

  private def validateLabelValue(value: String): Boolean = {
    val legalValue = (labelValuePattern.matches(value) || labelValueSingleCharFormat.matches(value))

    (!(value.contains("{") || value.size == 0)) && legalValue
  }

  implicit val labelValueReader = ConfigReader.fromCursor[LabelValue] { cur =>
    cur.asString.flatMap { str =>
      if (validateLabelValue(str)) {
        Right(LabelValue(str))
      } else {
        cur.failed(InvalidLabelFailure(s"$InvalidLabel value:${str}"))
      }
    }
  }

  implicit val labelValueWriter = ConfigWriter.fromFunction[LabelValue] { label: LabelValue =>
    ConfigValueFactory.fromAnyRef(label.value)
  }

  // LabelKey

  final case class LabelKey(key: String)

  private def hasPrefix(label: String) = {
    label.count(_ == '/') == 1 && !label.startsWith("/") && !label.endsWith("/")
  }

  private val illegalLabelPrefixPattern = """^[0-9\-]""".r
  private val labelPrefixPattern = """^[a-z0-9\.]{0,252}[a-z0-9]{0,1}$""".r
  private val labelPrefixSingleCharFormat = """^[a-zA-Z]{1}$""".r

  private def validatePrefix(prefix: String): Boolean = {

    val illegalPrefix = (prefix.size > 0 && illegalLabelPrefixPattern.matches(prefix))

    val legalPrefix = (labelPrefixPattern.matches(prefix) || labelPrefixSingleCharFormat.matches(prefix))

    !illegalPrefix && legalPrefix
  }

  private val labelNamePattern = """^[a-z0-9A-Z]{1}[a-z0-9A-Z\.\_\-]{0,61}[a-z0-9A-Z]{1}$""".r
  private val labelNameSingleCharFormat = "^[a-z0-9A-Z]{1}$".r

  private def validateKey(key: String): Boolean = {
    val legalKey = (labelNamePattern.matches(key) || labelNameSingleCharFormat.matches(key))

    (!(key.contains("{") || key.size == 0)) && legalKey
  }

  private def validKey(str: String) =
    if (hasPrefix(str)) {
      val splitted = str.split('/')
      val prefix = splitted(0)
      val key = splitted(1)

      validatePrefix(prefix) && validateKey(key)
    } else {
      validateKey(str)
    }

  implicit val labelsMapReader = genericMapReader[LabelKey, LabelValue] { str =>
    val valid = validKey(str)

    if (valid) {
      Right(LabelKey(str))
    } else {
      Left(InvalidLabelFailure(s"$InvalidLabel key:${str}"))
    }
  }

  implicit val labelsMapWriter = genericMapWriter[LabelKey, LabelValue] { lkey => lkey.key }

  // AnnotationKey
  final case class AnnotationKey(key: String)

  // AnnotationValue
  final case class AnnotationValue(value: String)

  implicit val annotationValueReader = ConfigReader.fromCursor[AnnotationValue] { cur =>
    cur.asString.flatMap { str =>
      // Not clear from kubernetes docs what would be invalid as annotation value, so not validating.
      Right(AnnotationValue(str))
    }
  }

  implicit val annotationValueWriter: ConfigWriter[AnnotationValue] = ConfigWriter.fromFunction[AnnotationValue] {
    av: AnnotationValue => ConfigValueFactory.fromAnyRef(av.value)
  }

  implicit val annotationsMapReader = genericMapReader[AnnotationKey, AnnotationValue] { str =>
    val valid = validKey(str)

    if (valid) {
      Right(AnnotationKey(str))
    } else {
      Left(InvalidAnnotationFailure(s"$InvalidAnnotation key:${str}"))
    }
  }

  implicit val annotationsMapWriter = genericMapWriter[AnnotationKey, AnnotationValue] { akey => akey.key }

  // Pod
  final case class Pod(
      labels: Map[LabelKey, LabelValue] = Map(),
      annotations: Map[AnnotationKey, AnnotationValue] = Map(),
      volumes: Map[String, Volume] = Map(),
      containers: Map[String, Container] = Map())

  implicit val podHint = ProductHint[Pod](allowUnknownKeys = false)

  val defaultPodReader = exportReader[Pod].instance

  implicit val podReader = defaultPodReader

  // Kubernetes
  final case class Kubernetes(pods: Map[String, Pod] = Map())

  implicit val kubernetesHint = ProductHint[Kubernetes](allowUnknownKeys = false)

  val defaultKubernetesReader = exportReader[Kubernetes].instance

  private def getInvalidVolumeMounts(pod: Pod, declaredVolumes: Seq[String]) = {
    val vmNames = pod.containers.values.map(_.volumeMounts.keys).flatten

    vmNames.collect {
      case vmName if !declaredVolumes.exists(_ == vmName) => vmName
    }
  }

  implicit val kubernetesReader = ConfigReader.fromCursor[Kubernetes] { cur: ConfigCursor =>
    defaultKubernetesReader.from(cur) match {
      case Right(kubernetes) =>
        val genericVolumes = kubernetes.pods.get("pod").map(_.volumes.keys.toSeq).getOrElse(Seq())
        val invalidMounts = kubernetes.pods.values.flatMap { pod =>
          getInvalidVolumeMounts(pod, genericVolumes ++ pod.volumes.keys)
        }

        if (invalidMounts.size > 0) {
          cur.failed(InvalidMountsFailure(s"$InvalidMounts ${invalidMounts.mkString(", ")}"))
        } else {
          val failures = kubernetes.pods.collect {
            case pod if (pod._1 == "job-manager" || pod._1 == "task-manager") && pod._2.labels.nonEmpty =>
              pod._1
          }
          if (failures.size > 0) cur.failed(PodConfigFailure(s"$LabelsNotAllowedOnPod ${failures.mkString(", ")}"))
          else Right(kubernetes)
        }
      case Left(err) =>
        cur.failed(ExceptionThrown(ConfigException(err.prettyPrint())))
    }
  }

  // Runtime
  final case class Runtime(config: Config = ConfigFactory.empty(), kubernetes: Kubernetes = Kubernetes())

  implicit val runtimeHint = ProductHint[Runtime](allowUnknownKeys = false)

  // Streamlet
  final case class Streamlet(
      configParameters: Config = ConfigFactory.empty(),
      config: Config = ConfigFactory.empty(),
      kubernetes: Kubernetes = Kubernetes())

  implicit val streamletHint = ProductHint[Streamlet](allowUnknownKeys = false)

  val defaultStreamletReader = exportReader[Streamlet].instance

  implicit val streamletReader = ConfigReader.fromCursor[Streamlet] { cur: ConfigCursor =>
    defaultStreamletReader.from(cur) match {
      case Right(s)
          if (s.configParameters.isEmpty &&
          s.config.isEmpty &&
          s.kubernetes.pods.isEmpty) =>
        cur.failed(StreamletConfigFailure(MandatorySectionsText))
      case Right(s) => Right(s)
      case Left(err) =>
        cur.failed(ExceptionThrown(ConfigException(err.prettyPrint())))
    }
  }

  // PortMapping
  final case class PortMapping(id: String, config: Config)

  implicit val portMappingHint = ProductHint[PortMapping](allowUnknownKeys = false)

  // PortMappings
  final case class PortMappings(portMappings: Map[String, PortMapping])

  implicit val portMappingsHint = ProductHint[PortMappings](
    ConfigFieldMapping(Map("portMappings" -> "port_mappings")),
    useDefaultArgs = true,
    allowUnknownKeys = false)

  // Context
  final case class Context(context: PortMappings)

  implicit val contextHint = ProductHint[Context](allowUnknownKeys = false)

  // Streamlet
  final case class StreamletContext(streamlet: Context)

  implicit val streamletContextHint = ProductHint[StreamletContext](allowUnknownKeys = false)

  // Topic
  final case class TopicConfig(
      name: Option[String] = None,
      partitions: Option[Int] = None,
      replicas: Option[Int] = None) {
    protected[CloudflowConfig] var topLevelConfig: Config = ConfigFactory.empty()
  }

  implicit val topicConfigHint = ProductHint[TopicConfig](allowUnknownKeys = true)

  val defaultTopicConfigReader = exportReader[TopicConfig].instance

  implicit val topicConfigReader = ConfigReader.fromCursor[TopicConfig] { cur: ConfigCursor =>
    defaultTopicConfigReader.from(cur) match {
      case Right(v) =>
        cur.asObjectCursor.foreach { top =>
          v.topLevelConfig = top.objValue.toConfig
        }
        Right(v)
      case Left(err) =>
        cur.failed(ExceptionThrown(ConfigException(err.prettyPrint())))
    }
  }

  private val defaultTopicConfigWriter = exportWriter[TopicConfig].instance

  implicit val topicConfigWriter: ConfigWriter[TopicConfig] = ConfigWriter.fromFunction[TopicConfig] {
    tc: TopicConfig => defaultTopicConfigWriter.to(tc).withFallback(tc.topLevelConfig)
  }

  // Topic
  final case class Topic(
      producers: List[String] = List(),
      consumers: List[String] = List(),
      cluster: Option[String] = None,
      managed: Boolean = true,
      connectionConfig: Config = ConfigFactory.empty(),
      producerConfig: Config = ConfigFactory.empty(),
      consumerConfig: Config = ConfigFactory.empty(),
      topic: TopicConfig = TopicConfig()) {
    protected[CloudflowConfig] var topLevelConfig: Config = ConfigFactory.empty()
  }

  implicit val topicHint = ProductHint[Topic](allowUnknownKeys = true)

  val defaultTopicReader = exportReader[Topic].instance

  implicit val topicReader = ConfigReader.fromCursor[Topic] { cur: ConfigCursor =>
    defaultTopicReader.from(cur) match {
      case Right(v) =>
        cur.asObjectCursor.foreach { top =>
          v.topLevelConfig = top.objValue.toConfig
        }
        Right(v)
      case Left(err) =>
        cur.failed(ExceptionThrown(ConfigException(err.prettyPrint())))
    }
  }

  private val defaultTopicWriter = exportWriter[Topic].instance

  implicit val topicWriter: ConfigWriter[Topic] = ConfigWriter.fromFunction[Topic] { t: Topic =>
    defaultTopicWriter.to(t).withFallback(t.topLevelConfig)
  }

  // Cloudflow
  final case class Cloudflow(
      streamlets: Map[String, Streamlet] = Map(),
      runtimes: Map[String, Runtime] = Map(),
      topics: Map[String, Topic] = Map(),
      runner: Option[StreamletContext] = None)

  implicit val cloudflowHint = ProductHint[Cloudflow](allowUnknownKeys = false)

  // CloudflowRoot
  final case class CloudflowRoot(cloudflow: Cloudflow = Cloudflow())

  implicit val cloudflowRootHint = ProductHint[CloudflowRoot](allowUnknownKeys = true)

  // Custom errors
  val MandatorySectionsText = "a streamlet should have at least one of the mandatory sections"

  private case class StreamletConfigFailure(msg: String) extends ConfigException(msg) with FailureReason {
    def description = msg
  }

  val LabelsNotAllowedOnPod = "Labels can NOT be applied specifically to"

  private case class PodConfigFailure(msg: String) extends ConfigException(msg) with FailureReason {
    def description = msg
  }

  val InvalidLabel = "Invalid label"

  private case class InvalidLabelFailure(msg: String) extends ConfigException(msg) with FailureReason {
    def description = msg
  }

  val InvalidAnnotation = "Invalid annotation"

  private case class InvalidAnnotationFailure(msg: String) extends ConfigException(msg) with FailureReason {
    def description = msg
  }

  val InvalidMounts = "Volume mounts without a corresponding declared volume"

  private case class InvalidMountsFailure(msg: String) extends ConfigException(msg) with FailureReason {
    def description = msg
  }

  def loadAndValidate(config: Config): Try[CloudflowConfig.CloudflowRoot] = {
    loadAndValidate(ConfigSource.fromConfig(config))
  }

  def loadAndValidate(config: ConfigObjectSource): Try[CloudflowConfig.CloudflowRoot] = {
    (config.load[CloudflowConfig.CloudflowRoot]) match {
      case Right(value) => Success(value)
      case Left(err) =>
        Failure(ConfigException(s"Configuration errors:\n${err.prettyPrint()}"))
    }
  }

  def defaultConfig(spec: App.Spec) = {
    val defaultConfig = CloudflowRoot(Cloudflow(streamlets = spec.streamlets.map { s =>
      s.name -> Streamlet(configParameters = ConfigFactory.parseMap(
        s.descriptor.configParameters
          .map { cp =>
            cp.key -> cp.defaultValue
          }
          .toMap
          .asJava))
    }.toMap))

    defaultConfig
  }

  def defaultMountsConfig(spec: App.Spec, allowedRuntimes: List[String]) = {
    val availableRuntimes = spec.deployments.map(_.runtime).filter { r => allowedRuntimes.exists(_ == r) }.distinct
    // format: off
    val defaultMounts = CloudflowRoot(Cloudflow(
      runtimes = availableRuntimes.map { runtime =>
        runtime -> Runtime(
          kubernetes = Kubernetes(
            pods = Map("pod" -> Pod(
              volumes = Map("default" -> PvcVolume(
                name = s"cloudflow-$runtime",
                readOnly = false)),
              containers = Map("container" -> Container(
                volumeMounts = Map("default" -> VolumeMount(
                  mountPath = s"/mnt/$runtime/storage",
                  readOnly = false))))))))
      }.toMap
    ))
    // format: on

    defaultMounts
  }

  def loggingMountsConfig(spec: App.Spec, loggingConfigHash: String) = {
    val allRuntimes = spec.deployments.map(_.runtime).distinct

    def pods() = {
      Map(
        "pod" -> Pod(
          volumes = Map(s"logging-${loggingConfigHash}" -> SecretVolume(name = "logging")),
          containers = Map("container" -> Container(volumeMounts =
            Map(s"logging-${loggingConfigHash}" -> VolumeMount(mountPath = s"/opt/logging", readOnly = true))))))
    }

    val loggingMounts = CloudflowRoot(Cloudflow(runtimes = allRuntimes.map { runtime =>
      runtime -> Runtime(kubernetes = Kubernetes(pods = pods()))
    }.toMap))

    loggingMounts
  }

  def writeTopic(topic: Topic) = {
    ConfigWriter[Topic].to(topic)
  }

  def writeConfig(config: CloudflowRoot) = {
    ConfigWriter[CloudflowRoot].to(config)
  }

  def runtimeConfig(streamletName: String, runtimeName: String, config: CloudflowRoot): Config = {
    val streamletConfig =
      config.cloudflow.streamlets
        .get(streamletName)
        .map(_.config)
        .getOrElse(ConfigFactory.empty())
    val runtimeConfig =
      config.cloudflow.runtimes
        .get(runtimeName)
        .map(_.config)
        .getOrElse(ConfigFactory.empty())

    streamletConfig.withFallback(runtimeConfig)
  }

  def podsConfig(streamletName: String, runtimeName: String, config: CloudflowRoot): Config = {
    val streamletConfig =
      config.cloudflow.streamlets
        .get(streamletName)
        .map(ConfigWriter[Streamlet].to(_))
        .getOrElse(ConfigFactory.empty())
    val runtimeConfig =
      config.cloudflow.runtimes
        .get(runtimeName)
        .map(ConfigWriter[Runtime].to(_))
        .getOrElse(ConfigFactory.empty())

    ConfigFactory
      .empty()
      .withFallback(streamletConfig)
      .withFallback(runtimeConfig)
      .withOnlyPath("kubernetes")
  }

  def streamletConfig(streamletName: String, runtimeName: String, config: CloudflowRoot): Config = {
    val streamletConfigParams =
      config.cloudflow.streamlets
        .get(streamletName)
        .map(_.configParameters)
        .getOrElse(ConfigFactory.empty())
        .root()

    val streamletRuntimeConfig = runtimeConfig(streamletName, runtimeName, config)
    val kubernetesConfig = podsConfig(streamletName, runtimeName, config)

    val streamletConfig = streamletRuntimeConfig.withFallback(kubernetesConfig)

    ConfigFactory
      .parseMap(Map(s"cloudflow.streamlets.$streamletName" -> streamletConfigParams).asJava)
      .withFallback(streamletConfig)
  }
}

object UnsafeCloudflowConfigLoader {

  implicit val podReader = CloudflowConfig.defaultPodReader
  implicit val kubernetesReader = CloudflowConfig.defaultKubernetesReader
  implicit val streamletReader = CloudflowConfig.defaultStreamletReader

  def load(config: Config): Try[CloudflowConfig.CloudflowRoot] = {
    (ConfigSource.fromConfig(config).load[CloudflowConfig.CloudflowRoot]) match {
      case Right(value) => Success(value)
      case Left(err) =>
        Failure(ConfigException(s"Configuration errors:\n${err.prettyPrint()}"))
    }
  }

  def loadPodConfig(config: Config): Try[CloudflowConfig.Kubernetes] = {
    (ConfigSource.fromConfig(config).load[CloudflowConfig.Streamlet]) match {
      case Right(value) => Success(value.kubernetes)
      case Left(err) =>
        Failure(ConfigException(s"Error in pod configuration:\n${err.prettyPrint()}"))
    }
  }

}

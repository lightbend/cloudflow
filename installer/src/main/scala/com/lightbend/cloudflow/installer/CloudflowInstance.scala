package cloudflow.installer

import play.api.libs.json._
import play.api.libs.json.JsonNaming.SnakeCase
import skuber._
import skuber.apiextensions._
import skuber.ResourceSpecification.Subresources

object CloudflowInstance {

  final case class KafkaClusterCR(name: String,
                                  version: String,
                                  kafkaPersistentStorageClass: String,
                                  zooKeeperPersistentStorageClass: String)

  final case class FlinkOperator(version: String, serviceAccount: String)
  final case class SparkOperator(version: String, image: String)
  final case class CloudflowOperator(imageTag: String, persistentStorageClass: String)

  case class Status(status: String, message: Option[String] = None)

  final case class Spec(kafkaClusterCR: KafkaClusterCR,
                        flinkOperator: FlinkOperator,
                        sparkOperator: SparkOperator,
                        cloudflowOperator: CloudflowOperator)

  type CR = CustomResource[Spec, Status]
  implicit val Definition = ResourceDefinition[CR](
    group = "cloudflow-installer.lightbend.com",
    version = "v1alpha1",
    kind = "Cloudflow",
    singular = Some("cloudflow"),
    plural = Some("cloudflows"),
    shortNames = List("cloudflow"),
    subresources = Some(Subresources().withStatusSubresource)
  )

  val CRD = CustomResourceDefinition[CR]

  def editor =
    new ObjectEditor[CR] {
      def updateMetadata(obj: CR, newMetadata: ObjectMeta): CR = obj.copy(metadata = newMetadata)
    }

  implicit val configFormat            = JsonConfiguration(SnakeCase)
  implicit val strimziOperatorFormat   = Json.format[KafkaClusterCR]
  implicit val flinkOperatorFormat     = Json.format[FlinkOperator]
  implicit val sparkOperatorFormat     = Json.format[SparkOperator]
  implicit val cloudflowOperatorFormat = Json.format[CloudflowOperator]
  implicit val specFmt                 = Json.format[Spec]
  implicit val statusFmt               = Json.format[Status]

  case class ValidationFailure(errorMsg: String)

  def validateClusterFeatures(instance: CloudflowInstance.CR, clusterFeatures: ClusterFeatures): List[ValidationFailure] =
    validateStorageClasses(instance, clusterFeatures.storageClasses)

  def validateStorageClasses(instance: CloudflowInstance.CR, storageClasses: Set[StorageClass]): List[ValidationFailure] = {

    def validatePersistentStorage(storageClasses: Set[StorageClass])(selectedStorageClass: String): Option[ValidationFailure] =
      if (!storageClasses.exists(sc => sc.name == selectedStorageClass))
        Some(ValidationFailure(s"The cluster does not have a '${selectedStorageClass}' storage class."))
      else
        None

    // Validate storage types
    List(
      instance.spec.cloudflowOperator.persistentStorageClass,
      instance.spec.kafkaClusterCR.kafkaPersistentStorageClass,
      instance.spec.kafkaClusterCR.zooKeeperPersistentStorageClass
    ).flatMap(validatePersistentStorage(storageClasses))
  }
}

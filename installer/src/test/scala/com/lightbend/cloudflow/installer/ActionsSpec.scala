package cloudflow.installer

import org.scalatest._

import scala.reflect.ClassTag

class ActionSpec extends WordSpec with MustMatchers with GivenWhenThen with EitherValues with Inspectors {

  "Install Cloudflow action" should {
      "contain actions with correct overlay values" in {

        implicit val cr     = TestInstance.get
        val clusterFeatures = ClusterFeatures()
        val actions         = Actions(clusterFeatures)
        val action          = actions.installCloudflow()

        implicit class ActionList(action: CompositeAction) {
          def getType[O: ClassTag] =
            action.childActions.collect {
              case action: O => action
            }.head
        }

        val cloudflowValues = action.getType[CloudflowOperatorManagedStrimzi].overlayValues.getOrElse(Map())
        cloudflowValues("cloudflowOperator.persistentStorageClass") mustBe "glusterfs-storage"
        cloudflowValues("kafkaClusterCr.kafkaPersistentStorageClass") mustBe "gp2"
        cloudflowValues("kafkaClusterCr.zookeeperPersistentStorageClass") mustBe "gp2"

        val flinkValues = action.getType[FlinkOperator].overlayValues.getOrElse(Map())
        flinkValues("flinkOperator.serviceAccount") mustBe "flink-service-account"
      }

      "contain actions with correct base overlay values" in {

        implicit val cr     = TestInstance.get
        val clusterFeatures = ClusterFeatures()
        val actions         = Actions(clusterFeatures)
        val action          = actions.installCloudflow()

        action.childActions
          .collect {
            case kubectlApply: KubectlApply => kubectlApply
          }
          .foreach { action =>
            action.asInstanceOf[KubectlApply].baseOverlayValues("namespace") mustBe cr.metadata.namespace
            action.asInstanceOf[KubectlApply].baseOverlayValues("instanceId") mustBe cr.metadata.name
          }
      }

    }
}

package cloudflow.installer

package cloudflow.installer
import akka.actor._
import org.scalatest._

class CloudflowInstanceSpec extends WordSpec with MustMatchers {

  implicit val system = ActorSystem()

  "CloudflowInstance" should {
      "validate correctly against a set of storage classes" in {
        val instance = TestInstance.get()
        val storageClasses = Set(StorageClass("glusterfs-storage", "kubernetes.io/glusterfs"), StorageClass("gp2", "kubernetes.io/aws-ebs"))
        val result = CloudflowInstance.validateStorageClasses(instance, storageClasses)

        result mustBe empty
      }
      "produce a validation error if storage class cannot be found by name" in {
        val instance = TestInstance.get()
        val storageClasses = Set(StorageClass("glusterfs-asfasdfasdf", "kubernetes.io/glusterfs"), StorageClass("gp2", "kubernetes.io/aws-ebs"))
        val result = CloudflowInstance.validateStorageClasses(instance, storageClasses)

        result must have size 1
        result must not be empty
      }
    }
}

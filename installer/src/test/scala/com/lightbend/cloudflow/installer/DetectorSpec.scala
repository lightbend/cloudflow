package cloudflow.installer

package cloudflow.installer
import akka.actor._
import org.scalatest._
import akka.event.LoggingAdapter
import scala.util.Try

class DetectorSpec extends WordSpec with MustMatchers {

  implicit val system   = ActorSystem()
  implicit val settings = Settings(system)
  implicit val logging  = system.log

  "Detector" should {
    "correctly create storage classes from input" in {

      def storageClassExecutor(commandLine: Array[String], log: LoggingAdapter, settings: Settings): Try[String] = {
        val _ = (commandLine, log, settings)

        Try("""gke-ssd             kubernetes.io/gce-pd      2d
            |glusterfs-storage   kubernetes.io/glusterfs   204d
            |gp2 (default)       kubernetes.io/aws-ebs     204d
            """.stripMargin)
      }
      val detector       = Detector(storageClassExecutor)
      val storageClasses = detector.getStorageClasses()

      storageClasses must have size 3
      storageClasses.find(_.name == "glusterfs-storage").get mustBe StorageClass("glusterfs-storage", "kubernetes.io/glusterfs")
      storageClasses.find(_.name == "gp2").get mustBe StorageClass("gp2", "kubernetes.io/aws-ebs")
      storageClasses.find(_.name == "gke-ssd").get mustBe StorageClass("gke-ssd", "kubernetes.io/gce-pd")
    }
    "not create storage classes, when no storage classes where found" in {

      def storageClassExecutor(commandLine: Array[String], log: LoggingAdapter, settings: Settings): Try[String] = {
        val _ = (commandLine, log, settings)

        Try("""No resources found in default namespace.
            """.stripMargin)
      }
      val detector       = Detector(storageClassExecutor)
      val storageClasses = detector.getStorageClasses()

      storageClasses must have size 0
    }
  }
}

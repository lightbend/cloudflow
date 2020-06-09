package cloudflow.installer

import akka.actor.ActorSystem
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import org.scalatest._

import scala.concurrent.Await
import scala.concurrent.duration._

class EventSpec extends WordSpec with MustMatchers with GivenWhenThen with EitherValues with Inspectors {

  implicit val system: ActorSystem    = ActorSystem("TestSystem")
  implicit val mat: ActorMaterializer = ActorMaterializer()
  implicit val ec                     = mat.executionContext
  implicit val log: LoggingAdapter    = Logging(system, "Cloudflow Installer")
  implicit val settings               = Settings(system)

  "Cloudflow event" should {
    "transform an install event to a install action" in {
      val instance        = TestInstance.get
      val clusterFeatures = ClusterFeatures()
      val installEvent    = InstallEvent(instance, None, instance.metadata.namespace, clusterFeatures)
      val future          = Source(List(installEvent)).via(CloudflowEvent.toAction).runWith(Sink.headOption)
      val result          = Await.result(future, 3.seconds)

      result must not be empty
      result.get mustBe a[CompositeAction]

      val action = result.get.asInstanceOf[CompositeAction]

      action.childActions must have size 5
      action.childActions(0) mustBe a[Strimzi]
      action.childActions(1) mustBe a[SparkOperator]
      action.childActions(2) mustBe a[FlinkOperator]
      action.childActions(3) mustBe a[CloudflowOperatorManagedStrimzi]
      action.childActions(4) mustBe a[PatchOwnerReferenceOfSparkMutatingWebhookConfig]
    }

    "transform an un-install event to an un-install action" in {
      val instance        = TestInstance.get
      val clusterFeatures = ClusterFeatures()

      val installEvent = UninstallEvent(instance, None, instance.metadata.namespace, clusterFeatures)
      val future       = Source(List(installEvent)).via(CloudflowEvent.toAction).runWith(Sink.headOption)
      val result       = Await.result(future, 3.seconds)

      result must not be empty
      result.get mustBe a[CompositeAction]

      val action = result.get.asInstanceOf[CompositeAction]

      action.childActions must have size 2
      action.childActions(0) mustBe a[RemoveCloudflowClusterwideResources]
      action.childActions(1) mustBe a[RemoveCloudflowNamespacedResources]
    }

    "verify that detected cluster features are present" in {
      val instance        = TestInstance.get
      val clusterFeatures = ClusterFeatures(hasSecurityContextConstraints = true)
      val installEvent    = InstallEvent(instance, None, instance.metadata.namespace, clusterFeatures)
      val future          = Source(List(installEvent)).via(CloudflowEvent.toAction).runWith(Sink.headOption)
      val result          = Await.result(future, 3.seconds)

      result must not be empty
      result.get mustBe a[CompositeAction]

      val action = result.get.asInstanceOf[CompositeAction]

      action.childActions must have size 6
      action.childActions(0) mustBe a[Strimzi]
      action.childActions(1) mustBe a[SparkOperator]
      action.childActions(2) mustBe a[FlinkOperator]
      action.childActions(3) mustBe a[AddSccToSparkServiceAccount]
      action.childActions(4) mustBe a[CloudflowOperatorManagedStrimzi]
      action.childActions(5) mustBe a[PatchOwnerReferenceOfSparkMutatingWebhookConfig]
    }
  }

  "transform an pre-requisite failure event to an no operation action" in {
    val instance = TestInstance.get

    val failures     = List(CloudflowInstance.ValidationFailure("The cluster does not have a storage class named 'test'"))
    val installEvent = PreRequisiteFailed(instance, failures)
    val future       = Source(List(installEvent)).via(CloudflowEvent.toAction).runWith(Sink.headOption)
    val result       = Await.result(future, 3.seconds)

    result must not be empty
    result.get mustBe a[UpdateCRStatusAction]

    val action = result.get.asInstanceOf[UpdateCRStatusAction]
    val caught = Await.result(action.execute(), 3.seconds)
    caught.stdErr mustBe Some("The cluster does not have a storage class named 'test'")
  }
}

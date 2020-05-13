package cloudflow.installer

import akka.NotUsed
import akka.event.LoggingAdapter
import akka.stream.scaladsl._
import skuber._
import skuber.api.client._

sealed trait CloudflowEvent

case class InstallEvent(instance: CloudflowInstance.CR,
                        currentInstance: Option[CloudflowInstance.CR],
                        namespace: String,
                        availableClusterFeatures: ClusterFeatures)
    extends CloudflowEvent

case class UninstallEvent(instance: CloudflowInstance.CR,
                          currentInstance: Option[CloudflowInstance.CR],
                          namespace: String,
                          availableClusterFeatures: ClusterFeatures)
    extends CloudflowEvent
case class PreRequisiteFailed(instance: CloudflowInstance.CR, validationFailures: List[CloudflowInstance.ValidationFailure])
    extends CloudflowEvent

object CloudflowEvent {

  def fromWatchEvent[O <: ObjectResource]()(implicit log: LoggingAdapter, settings: Settings) =
    Flow[WatchEvent[CloudflowInstance.CR]]
      .statefulMapConcat { () ⇒
        var currentInstances = Map[String, WatchEvent[CloudflowInstance.CR]]()
        watchEvent ⇒ {
          val instance        = watchEvent._object
          val namespace       = instance.metadata.namespace
          val id              = instance.metadata.name
          val currentInstance = currentInstances.get(id).map(_._object)
          val detector        = Detector()

          val clusterFeatures = detector.detectClusterFeatures()

          clusterFeatures.print

          watchEvent._type match {
            // case EventType.DELETED ⇒
            //   currentInstances = currentInstances - id
            //   List(UninstallEvent(instance, currentInstance, namespace, clusterFeatures))
            case EventType.ADDED | EventType.MODIFIED ⇒
              if (currentInstances.get(id).forall { existingEvent ⇒
                    existingEvent._object.resourceVersion != watchEvent._object.resourceVersion &&
                    // the spec must change, otherwise it is not a deploy event (but likely a status update).
                    existingEvent._object.spec != watchEvent._object.spec
                  }) {
                currentInstances = currentInstances + (id -> watchEvent)

                val validationFailures = CloudflowInstance.validateClusterFeatures(instance, clusterFeatures)
                if (validationFailures.nonEmpty) {
                  List(PreRequisiteFailed(instance, validationFailures))
                } else {
                  List(InstallEvent(instance, currentInstance, namespace, clusterFeatures))
                }

              } else List.empty
            case _ =>
              List.empty
          }
        }
      }

  def toAction[O <: ObjectResource](): Flow[CloudflowEvent, Action, NotUsed] =
    Flow[CloudflowEvent].map {
      case install: InstallEvent =>
        implicit val c = install.instance
        Actions(install.availableClusterFeatures).installCloudflow
      case uninstall: UninstallEvent =>
        implicit val c = uninstall.instance
        Actions(uninstall.availableClusterFeatures).uninstallCloudflow
      case failure: PreRequisiteFailed =>
        implicit val c = failure.instance
        UpdateCRStatusAction(failure.validationFailures)
    }
}

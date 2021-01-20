/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.operator

import scala.concurrent._
import scala.concurrent.duration._
import scala.reflect._
import scala.util._
import scala.util.control.NonFatal

import akka.NotUsed
import akka.actor._
import akka.pattern._
import akka.stream._
import akka.stream.scaladsl._
import org.slf4j.LoggerFactory
import play.api.libs.json.Format
import skuber._
import skuber.api.client._
import skuber.json.format._

import cloudflow.operator.action._
import cloudflow.operator.action.runner.Runner
import cloudflow.operator.event._
import cloudflow.operator.flow._

object Operator {
  lazy val log = LoggerFactory.getLogger("Operator")

  val ProtocolVersion              = "5"
  val ProtocolVersionKey           = "protocol-version"
  val ProtocolVersionConfigMapName = "cloudflow-protocol-version"
  def ProtocolVersionConfigMap(ownerReferences: List[OwnerReference]) = ConfigMap(
    metadata = ObjectMeta(name = ProtocolVersionConfigMapName,
                          labels = Map(ProtocolVersionConfigMapName -> ProtocolVersionConfigMapName),
                          ownerReferences = ownerReferences),
    data = Map(ProtocolVersionKey -> ProtocolVersion)
  )

  val AppIdLabel         = "com.lightbend.cloudflow/app-id"
  val ConfigFormatLabel  = "com.lightbend.cloudflow/config-format"
  val StreamletNameLabel = "com.lightbend.cloudflow/streamlet-name"
  val ConfigUpdateLabel  = "com.lightbend.cloudflow/config-update"

  val DefaultWatchOptions = ListOptions(
    labelSelector = Some(LabelSelector(LabelSelector.IsEqualRequirement(CloudflowLabels.ManagedBy, CloudflowLabels.ManagedByCloudflow))),
    resourceVersion = None
  )

  val EventWatchOptions = ListOptions()

  val MaxObjectBufSize = 8 * 1024 * 1024

  val restartSettings = RestartSettings(
    minBackoff = 3.seconds,
    maxBackoff = 30.seconds,
    randomFactor = 0.2
  )

  val decider: Supervision.Decider = {
    case _ => Supervision.Stop
  }

  val StreamAttributes = ActorAttributes.supervisionStrategy(decider)

  def handleAppEvents(
      client: KubernetesClient,
      runners: Map[String, Runner[_]],
      podName: String,
      podNamespace: String
  )(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext) = {
    val logAttributes  = Attributes.logLevels(onElement = Attributes.LogLevels.Info)
    val actionExecutor = new SkuberActionExecutor()

    runStream(
      watch[CloudflowApplication.CR](client, DefaultWatchOptions)
        .via(AppEventFlow.fromWatchEvent(logAttributes))
        .via(AppEventFlow.toAction(runners, podName, podNamespace))
        .via(executeActions(actionExecutor, logAttributes))
        .toMat(Sink.ignore)(Keep.right)
        .mapMaterializedValue {
          _.flatMap { value =>
            // close Kafka admin clients
            TopicActions.KafkaAdmins
              .close(10.seconds)
              .map(_ => value)
          }
        },
      "The actions stream completed unexpectedly, terminating.",
      "The actions stream failed, terminating."
    )
  }

  def handleConfigurationUpdates(
      client: KubernetesClient,
      runners: Map[String, Runner[_]],
      podName: String
  )(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext) = {
    val logAttributes  = Attributes.logLevels(onElement = Attributes.LogLevels.Info)
    val actionExecutor = new SkuberActionExecutor()
    // only watch secrets that contain output config
    val watchOptions = ListOptions(
      labelSelector = Some(
        LabelSelector(
          LabelSelector.IsEqualRequirement(CloudflowLabels.ManagedBy, CloudflowLabels.ManagedByCloudflow),
          LabelSelector.IsEqualRequirement(CloudflowLabels.ConfigFormat, CloudflowLabels.StreamletDeploymentConfigFormat)
        )
      ),
      resourceVersion = None
    )

    runStream(
      watch[Secret](client, watchOptions)
        .via(StreamletChangeEventFlow.fromWatchEvent())
        .via(mapToAppInSameNamespace(client))
        .via(StreamletChangeEventFlow.toConfigUpdateAction(runners, podName))
        .via(executeActions(actionExecutor, logAttributes))
        .toMat(Sink.ignore)(Keep.right),
      "The config updates stream completed unexpectedly, terminating.",
      "The config updates stream failed, terminating."
    )
  }

  def handleStatusUpdates(
      client: KubernetesClient,
      runners: Map[String, Runner[_]]
  )(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext) = {
    val logAttributes  = Attributes.logLevels(onElement = Attributes.LogLevels.Debug)
    val actionExecutor = new SkuberActionExecutor()
    runStream(
      watch[Pod](client, DefaultWatchOptions)
        .via(StatusChangeEventFlow.fromWatchEvent())
        .log("status-change-event", StatusChangeEvent.detected)
        .via(mapToAppInSameNamespace(client))
        .via(StatusChangeEventFlow.toStatusUpdateAction(runners))
        .via(executeActions(actionExecutor, logAttributes))
        .toMat(Sink.ignore)(Keep.right),
      "The status changes stream completed unexpectedly, terminating.",
      "The status changes stream failed, terminating."
    )
  }
  private def executeActions(actionExecutor: ActionExecutor, logAttributes: Attributes): Flow[Action, Action, NotUsed] =
    Flow[Action]
      .mapAsync(1)(action => actionExecutor.execute(action))
      .log("action", Action.executed)
      .withAttributes(logAttributes)

  /**
   * TODO rewrite using `ProvidedAction`, ensuring all K8s effects are executed in executeActions.
   * Finds the associated [[CloudflowApplication.CR]]s for [[AppChangeEvent]]s.
   * The resulting flow outputs tuples of the app and the streamlet change event.
   */
  def mapToAppInSameNamespace[O <: ObjectResource, E <: AppChangeEvent[_]](
      client: KubernetesClient
  )(implicit ec: ExecutionContext): Flow[E, (Option[CloudflowApplication.CR], E), NotUsed] =
    Flow[E].mapAsync(1) { changeEvent =>
      val ns = changeEvent.namespace
      client.usingNamespace(ns).getOption[CloudflowApplication.CR](changeEvent.appId).map { cr =>
        cr -> changeEvent
      }
    }

  // NOTE: This watch can produce duplicate ADD events on startup, since it turns current resources into watch events,
  // and concatenates results of a subsequent watch. This can be improved.
  private def watch[O <: ObjectResource](
      client: KubernetesClient,
      options: ListOptions
  )(implicit system: ActorSystem,
    fmt: Format[O],
    lfmt: Format[ListResource[O]],
    rd: ResourceDefinition[O],
    lc: LoggingContext,
    ec: ExecutionContext,
    ct: ClassTag[O]): Source[WatchEvent[O], NotUsed] =
    /* =================================================
     * Workaround for issue found on openshift:
     * After 10-15 minutes, K8s API server responds with 410 Gone status to a watch request, which skuber does not expect while processing the watch response stream.
     * The resourceVersion of the watch is reported as too old by the K8s API server.
     *
     * listing resources and starting from that resourceVersion does not solve the issue.
     * This could be related to this issue:
     *
     * https://github.com/openshift/origin/issues/21636
     *
     * In the issue it states that openshift 3.11 runs with a default watch cache size set to 0.
     * Workaround: because of this issue, any K8SException during the watch here is ignored and the Source is replaced with recoverWithRetries
     * which restarts the process of first listing resources, turning current resources into watch events,
     * and concatenating results of a subsequent watch.
     * On failing watches this code becomes a polling loop of listing resources which are turned into events.
     * Events that have already been processed are discarded in AppEvents.fromWatchEvent.
     * ==================================================*/

    RestartSource.withBackoff(restartSettings) { () =>
      log.info(s"Starting watch for ${classTag[O].runtimeClass.getName}")
      Source
        .future(getCurrentEvents[O](client, options, 0, 3.seconds))
        .mapConcat(identity _)
        .concat(
          client
            .watchWithOptions[O](options = options, bufsize = MaxObjectBufSize)
            .map { o =>
              log.debug(s"""WatchEvent for ${classTag[O].runtimeClass.getName}, object uid: ${o._object.metadata.uid}""")
              o
            }
            .mapMaterializedValue(_ => NotUsed)
        )
    }

  private def getCurrentEvents[O <: ObjectResource](
      client: KubernetesClient,
      options: ListOptions,
      attempt: Int,
      delay: FiniteDuration
  )(implicit lfmt: Format[ListResource[O]],
    rd: ResourceDefinition[O],
    lc: LoggingContext,
    system: ActorSystem,
    ec: ExecutionContext,
    ct: ClassTag[O]): Future[List[WatchEvent[O]]] = {
    log.info(s"Getting current events for ${classTag[O].runtimeClass.getName}")

    (for {
      namespaces <- client.getNamespaceNames
      lists      <- Future.sequence(namespaces.map(ns => client.usingNamespace(ns).listWithOptions[ListResource[O]](options)))
      items       = lists.flatMap(_.items)
      watchEvents = items.map(item => WatchEvent(EventType.ADDED, item))
      itemUids    = items.map(_.metadata.uid)
      _           = log.debug(s"""Current ${classTag[O].runtimeClass.getName} objects: ${itemUids.mkString(",")}""")
    } yield watchEvents).recoverWith {
      case NonFatal(e) =>
        log.warn(s"Could not get current events, attempt number $attempt", e)
        after(delay, system.scheduler)(getCurrentEvents(client, options, attempt + 1, delay))
    }
  }
  private def runStream(
      graph: RunnableGraph[Future[_]],
      unexpectedCompletionMsg: String,
      errorMsg: String
  )(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext) =
    graph.withAttributes(StreamAttributes).run().onComplete {
      case Success(_) =>
        log.warn(unexpectedCompletionMsg)
        system.registerOnTermination(exitWithFailure())
        system.terminate()

      case Failure(t) =>
        log.error(errorMsg, t)
        system.registerOnTermination(exitWithFailure())
        system.terminate()
    }

  private def exitWithFailure() = System.exit(-1)
}

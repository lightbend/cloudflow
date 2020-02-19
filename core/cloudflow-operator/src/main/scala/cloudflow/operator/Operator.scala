/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
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

import akka.NotUsed
import akka.actor._
import akka.stream._
import akka.stream.scaladsl._
import cloudflow.operator.action._
import cloudflow.operator.event._
import play.api.libs.json.Format
import skuber._
import skuber.api.client._
import skuber.json.format._

import scala.concurrent._
import scala.util._

object Operator {
  val ProtocolVersion              = "1"
  val ProtocolVersionKey           = "protocol-version"
  val ProtocolVersionConfigMapName = "cloudflow-protocol-version"
  def ProtocolVersionConfigMap(ownerReferences: CloudflowOwnerReferences) = ConfigMap(
    // TODO: ownerReference
    metadata = ObjectMeta(name = ProtocolVersionConfigMapName,
                          labels = Map(ProtocolVersionConfigMapName -> ProtocolVersionConfigMapName),
                          ownerReferences = ownerReferences.list),
    data = Map(ProtocolVersionKey -> ProtocolVersion)
  )

  val AppIdLabel         = "com.lightbend.cloudflow/app-id"
  val StreamletNameLabel = "com.lightbend.cloudflow/streamlet-name"
  val ConfigUpdateLabel  = "com.lightbend.cloudflow/config-update"

  val DefaultWatchOptions = ListOptions(
    labelSelector = Some(LabelSelector(LabelSelector.IsEqualRequirement(CloudflowLabels.ManagedBy, CloudflowLabels.ManagedByCloudflow))),
    resourceVersion = None
  )

  val EventWatchOptions = ListOptions()

  val MaxObjectBufSize = 8 * 1024 * 1024

  def handleAppEvents(
      client: KubernetesClient
  )(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext, ctx: DeploymentContext) = {
    val logAttributes  = Attributes.logLevels(onElement = Attributes.LogLevels.Info)
    val actionExecutor = new SkuberActionExecutor()

    runStream(
      watch[CloudflowApplication.CR](client)
        .via(AppEvent.fromWatchEvent(logAttributes))
        .via(AppEvent.toAction)
        .via(executeActions(actionExecutor, logAttributes))
        .toMat(Sink.ignore)(Keep.right),
      "The actions stream completed unexpectedly, terminating.",
      "The actions stream failed, terminating."
    )
  }

  def handleConfigurationUpdates(
      client: KubernetesClient
  )(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext, ctx: DeploymentContext) = {
    val logAttributes  = Attributes.logLevels(onElement = Attributes.LogLevels.Info)
    val actionExecutor = new SkuberActionExecutor()

    runStream(
      watch[Secret](client)
        .via(StreamletChangeEvent.fromWatchEvent(modifiedOnly = true))
        .log("config-change-event", ConfigChangeEvent.detected)
        .via(StreamletChangeEvent.mapToAppInSameNamespace(client))
        .via(StreamletChangeEvent.toConfigUpdateAction)
        .via(executeActions(actionExecutor, logAttributes))
        .toMat(Sink.ignore)(Keep.right),
      "The config updates stream completed unexpectedly, terminating.",
      "The config updates stream failed, terminating."
    )
  }

  def handleStatusUpdates(client: KubernetesClient)(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext) = {
    val logAttributes  = Attributes.logLevels(onElement = Attributes.LogLevels.Info)
    val actionExecutor = new SkuberActionExecutor()
    runStream(
      watch[Pod](client)
        .via(StreamletChangeEvent.fromWatchEvent())
        .log("status-change-event", StatusChangeEvent.detected)
        .via(StreamletChangeEvent.mapToAppInSameNamespace(client))
        .via(StreamletChangeEvent.toStatusUpdateAction)
        .via(executeActions(actionExecutor, logAttributes))
        .toMat(Sink.ignore)(Keep.right),
      "The status changes stream completed unexpectedly, terminating.",
      "The status changes stream failed, terminating."
    )
  }

  private def executeActions(actionExecutor: ActionExecutor,
                             logAttributes: Attributes): Flow[Action[ObjectResource], Action[ObjectResource], NotUsed] =
    Flow[Action[ObjectResource]]
      .mapAsync(1)(actionExecutor.execute)
      .log("action", Action.executed)
      .withAttributes(logAttributes)

  private def watch[O <: ObjectResource](
      client: KubernetesClient,
      options: ListOptions = DefaultWatchOptions
  )(implicit system: ActorSystem,
    fmt: Format[O],
    lfmt: Format[ListResource[O]],
    rd: ResourceDefinition[O],
    lc: LoggingContext,
    ec: ExecutionContext): Source[WatchEvent[O], NotUsed] = {

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

    val eventsResult = getCurrentEvents[O](client, options)

    Source
      .fromFuture(eventsResult)
      .mapConcat(identity)
      .concat(
        client
          .watchWithOptions[O](options = options, bufsize = MaxObjectBufSize)
          .mapMaterializedValue(_ ⇒ NotUsed)
      )
      .recoverWithRetries(
        -1, {
          case _: TcpIdleTimeoutException ⇒
            watch[O](client)
          case e: skuber.api.client.K8SException ⇒
            println(s"""Ignoring Skuber K8SException (status message: '${e.status.message.getOrElse("")}'.)""")
            watch[O](client)
        }
      )
  }

  private def getCurrentEvents[O <: ObjectResource](
      client: KubernetesClient,
      options: ListOptions
  )(implicit lfmt: Format[ListResource[O]],
    rd: ResourceDefinition[O],
    lc: LoggingContext,
    ec: ExecutionContext): Future[List[WatchEvent[O]]] =
    for {
      namespaces ← client.getNamespaceNames
      lists      ← Future.sequence(namespaces.map(ns ⇒ client.usingNamespace(ns).listWithOptions[ListResource[O]](options)))
      watchEvents = lists.flatMap(_.items.map(item ⇒ WatchEvent(EventType.ADDED, item)))
    } yield watchEvents

  private def runStream(
      graph: RunnableGraph[Future[_]],
      unexpectedCompletionMsg: String,
      errorMsg: String
  )(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext) =
    graph.run.onComplete {
      case Success(_) ⇒
        system.log.warning(unexpectedCompletionMsg)
        system.registerOnTermination(exitWithFailure)
        system.terminate()

      case Failure(t) ⇒
        system.log.error(t, errorMsg)
        system.registerOnTermination(exitWithFailure)
        system.terminate()
    }

  private def exitWithFailure() = System.exit(-1)
}

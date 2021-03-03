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
import scala.util._
import akka.NotUsed
import akka.actor._
import akka.datap.crd.App
import akka.kube.actions.{ Action, ActionExecutor, Fabric8ActionExecutor }
import akka.stream._
import akka.stream.scaladsl._
import org.slf4j.LoggerFactory
import cloudflow.operator.action._
import cloudflow.operator.action.runner.Runner
import cloudflow.operator.event._
import cloudflow.operator.flow._
import io.fabric8.kubernetes.api.model.{
  ConfigMapBuilder,
  HasMetadata,
  KubernetesResource,
  OwnerReference,
  Pod,
  PodList,
  Secret,
  SecretList,
  WatchEvent,
  WatchEventBuilder
}
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.OperationContext
import io.fabric8.kubernetes.client.informers.{ EventType, ResourceEventHandler, SharedInformerFactory }
import io.fabric8.kubernetes.client.informers.cache.Cache

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import scala.jdk.CollectionConverters._

object Operator {
  lazy val log = LoggerFactory.getLogger("Operator")

  val ProtocolVersion = "5"
  val ProtocolVersionKey = "protocol-version"
  val ProtocolVersionConfigMapName = "cloudflow-protocol-version"
  def ProtocolVersionConfigMap(ownerReferences: List[OwnerReference]) = {
    new ConfigMapBuilder()
      .withNewMetadata()
      .withName(ProtocolVersionConfigMapName)
      .withLabels((Map(ProtocolVersionConfigMapName -> ProtocolVersionConfigMapName)).asJava)
      .withOwnerReferences(ownerReferences: _*)
      .endMetadata()
      .withData(Map(ProtocolVersionKey -> ProtocolVersion).asJava)
      .build()
  }

  val AppIdLabel = "com.lightbend.cloudflow/app-id"
  val ConfigFormatLabel = "com.lightbend.cloudflow/config-format"
  val StreamletNameLabel = "com.lightbend.cloudflow/streamlet-name"
  val ConfigUpdateLabel = "com.lightbend.cloudflow/config-update"

  val DefaultWatchOptions = Map(CloudflowLabels.ManagedBy -> CloudflowLabels.ManagedByCloudflow)

  val MaxObjectBufSize = 8 * 1024 * 1024

  val restartSettings = RestartSettings(minBackoff = 3.seconds, maxBackoff = 30.seconds, randomFactor = 0.2)

  val decider: Supervision.Decider = {
    case _ => Supervision.Stop
  }

  val StreamAttributes = ActorAttributes.supervisionStrategy(decider)

  private lazy val fabric8ExecutionContext = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())

  def handleEvents(client: KubernetesClient, runners: Map[String, Runner[_]], podName: String, podNamespace: String)(
      implicit system: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext) = {

    // handleAppEvents
    val logAttributes = Attributes.logLevels(onElement = Attributes.LogLevels.Info)
    val actionExecutor = new Fabric8ActionExecutor(client, fabric8ExecutionContext)

    val sharedInformerFactory = client.informers()

    runStream(
      watchCr(sharedInformerFactory, DefaultWatchOptions)
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
      "The actions stream failed, terminating.")

    // handleConfigurationUpdates
    // only watch secrets that contain output config
    val watchOptions = Map(
      CloudflowLabels.ManagedBy -> CloudflowLabels.ManagedByCloudflow,
      CloudflowLabels.ConfigFormat -> CloudflowLabels.StreamletDeploymentConfigFormat)

    runStream(
      watchSecret(sharedInformerFactory, watchOptions)
        .via(StreamletChangeEventFlow.fromWatchEvent())
        .via(mapToAppInSameNamespace(client))
        .via(StreamletChangeEventFlow.toConfigUpdateAction(runners, podName))
        .via(executeActions(actionExecutor, logAttributes))
        .toMat(Sink.ignore)(Keep.right),
      "The config updates stream completed unexpectedly, terminating.",
      "The config updates stream failed, terminating.")

    // handleStatusUpdates
    runStream(
      watchPod(sharedInformerFactory, DefaultWatchOptions)
        .via(StatusChangeEventFlow.fromWatchEvent())
        .log("status-change-event", StatusChangeEvent.detected)
        .via(mapToAppInSameNamespace(client))
        .via(StatusChangeEventFlow.toStatusUpdateAction(runners))
        .via(executeActions(actionExecutor, logAttributes))
        .toMat(Sink.ignore)(Keep.right),
      "The status changes stream completed unexpectedly, terminating.",
      "The status changes stream failed, terminating.")

    sharedInformerFactory.startAllRegisteredInformers()
  }

  private def executeActions(actionExecutor: ActionExecutor, logAttributes: Attributes): Flow[Action, Action, NotUsed] =
    Flow[Action]
      .mapAsync(1)(action => actionExecutor.execute(action))
      .withAttributes(logAttributes)

  /**
   * TODO rewrite using `ProvidedAction`, ensuring all K8s effects are executed in executeActions.
   * Finds the associated [[CloudflowApplication.CR]]s for [[AppChangeEvent]]s.
   * The resulting flow outputs tuples of the app and the streamlet change event.
   */
  def mapToAppInSameNamespace[E <: AppChangeEvent[_]](client: KubernetesClient)(
      implicit ec: ExecutionContext): Flow[E, (Option[App.Cr], E), NotUsed] =
    Flow[E].mapAsync(1) { changeEvent =>
      val ns = changeEvent.namespace

      Future {
        Option(
          client
            .customResources(App.customResourceDefinitionContext, classOf[App.Cr], classOf[App.List])
            .inNamespace(ns)
            .withName(changeEvent.appId)
            .get())
      }.map { cr => cr -> changeEvent }
    }

  private def getEventHandler[T <: KubernetesResource](fn: WatchEvent => Unit) = {

    new ResourceEventHandler[T]() {
      override def onAdd(elem: T): Unit = {
        val we = new WatchEventBuilder()
          .withType(EventType.ADDITION.toString)
          .build()

        // withObject in the builder doesn't work
        we.setObject(elem)
        // TODO: remove all the WatchEvent logic ... it's not needed possibly ... at least not like this
        fn(we)
      }

      override def onUpdate(oldElem: T, newElem: T): Unit = {
        val we = new WatchEventBuilder()
          .withType(EventType.UPDATION.toString)
          .build()

        // withObject in the builder doesn't work
        we.setObject(newElem)
        // TODO: remove all the WatchEvent logic ... it's not needed possibly ... at least not like this
        fn(we)
      }

      override def onDelete(elem: T, deletedFinalStateUnknown: Boolean): Unit = {
        val we = new WatchEventBuilder()
          .withType(EventType.DELETION.toString)
          .build()

        // withObject in the builder doesn't work
        we.setObject(elem)
        // TODO: remove all the WatchEvent logic ... it's not needed possibly ... at least not like this
        fn(we)
      }
    }
  }

  private def watchCr(sharedInformerFactory: SharedInformerFactory, options: Map[String, String])(
      implicit system: ActorSystem): Source[WatchEvent, NotUsed] = {

    // TODO probably should be an atomic ref as well
    val informer =
      sharedInformerFactory
        .sharedIndexInformerForCustomResource(
          App.customResourceDefinitionContext,
          classOf[App.Cr],
          classOf[App.List],
          new OperationContext().withLabels(options.asJava),
          1000L * 60L * 10L)

    // TODO: IMPORTANT we can remove all of the watchEvent reference, no need for them with fabric8
    val (sourceMat, source) = {
      Source
        .queue[WatchEvent](10000)
        .preMaterialize()
    }

    def enqueueTask(event: WatchEvent): Unit = {
      val key = Cache.metaNamespaceKeyFunc(event.getObject)

      log.info("Enqueue CrChanged with key [{}]", key)

      sourceMat.offer(event)
    }

    informer.addEventHandler(getEventHandler[App.Cr](enqueueTask))

    source
  }

  private def watchSecret(sharedInformerFactory: SharedInformerFactory, options: Map[String, String])(
      implicit system: ActorSystem): Source[WatchEvent, NotUsed] = {

    // TODO probably should be an atomic ref as well
    val informer =
      sharedInformerFactory
        .sharedIndexInformerFor(
          classOf[Secret],
          classOf[SecretList],
          new OperationContext().withLabels(options.asJava),
          1000L * 60L * 10L)

    // TODO: IMPORTANT we can remove all of the watchEvent reference, no need for them with fabric8
    val (sourceMat, source) = {
      Source
        .queue[WatchEvent](10000)
        .preMaterialize()
    }

    def enqueueTask(event: WatchEvent): Unit = {
      val key = Cache.metaNamespaceKeyFunc(event.getObject)

      log.info("Enqueue SecretChanged with key [{}]", key)

      sourceMat.offer(event)
    }

    informer.addEventHandler(getEventHandler[Secret](enqueueTask))

    source
  }

  private def watchPod(sharedInformerFactory: SharedInformerFactory, options: Map[String, String])(
      implicit system: ActorSystem): Source[WatchEvent, NotUsed] = {

    // TODO probably should be an atomic ref as well
    val informer =
      sharedInformerFactory
        .sharedIndexInformerFor(
          classOf[Pod],
          classOf[PodList],
          new OperationContext().withLabels(options.asJava),
          1000L * 60L * 10L)

    // TODO: IMPORTANT we can remove all of the watchEvent reference, no need for them with fabric8
    val (sourceMat, source) = {
      Source
        .queue[WatchEvent](10000)
        .preMaterialize()
    }

    def enqueueTask(event: WatchEvent): Unit = {
      val key = Cache.metaNamespaceKeyFunc(event.getObject)

      log.info("Enqueue PodChanged with key [{}]", key)

      sourceMat.offer(event)
    }

    informer.addEventHandler(getEventHandler[Pod](enqueueTask))

    source
  }

  private def runStream(graph: RunnableGraph[Future[_]], unexpectedCompletionMsg: String, errorMsg: String)(
      implicit system: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext) =
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

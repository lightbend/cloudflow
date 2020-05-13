package cloudflow.installer

import akka.NotUsed
import akka.actor._
import akka.stream._
import akka.stream.scaladsl._
import play.api.libs.json.Format
import skuber._
import skuber.api.Configuration
import skuber.api.client._

import scala.concurrent._
import scala.util._
import akka.event.LoggingAdapter
import cloudflow.installer.CloudflowInstance.CR

object Operator {
  val ProtocolVersionNamespace     = "lightbend"
  val ProtocolVersion              = "1"
  val ProtocolVersionKey           = "protocol-version"
  val ProtocolVersionConfigMapName = "cloudflow-protocol-version"
  val ProtocolVersionConfigMap = ConfigMap(
    metadata = ObjectMeta(name = ProtocolVersionConfigMapName),
    data = Map(ProtocolVersionKey -> ProtocolVersion)
  )

  val DefaultWatchOptions = ListOptions(
    labelSelector = Some(LabelSelector(LabelSelector.IsEqualRequirement(CloudflowLabels.ManagedBy, CloudflowLabels.ManagedByCloudflow))),
    resourceVersion = None
  )

  val MaxResponseSize = 1024 * 1024 * 8

  def handleEvents(
      client: KubernetesClient
  )(implicit system: ActorSystem, mat: ActorMaterializer, ec: ExecutionContext, log: LoggingAdapter, settings: Settings): Unit =
    runStream(
      watch[CloudflowInstance.CR](client)
        .via(CloudflowEvent.fromWatchEvent)
        .via(CloudflowEvent.toAction())
        .via(executeActions(KubectlActionExecutor))
        .via(updateStatus(Configuration.defaultK8sConfig))
        .via(logStatus())
        .toMat(Sink.ignore)(Keep.right),
      "The actions stream completed unexpectedly, terminating.",
      "The actions stream failed, terminating."
    )

  private def logStatus()(implicit ec: ExecutionContext, log: LoggingAdapter): Flow[ActionResult, ActionResult, NotUsed] =
    Flow[ActionResult]
      .mapAsync(1) { result =>
        result match {
          case s: ActionSuccess =>
            log.info(s"The action `${s.action.name}` was successfully executed for instance ${s.action.instance.metadata.name}")
          case f: ActionFailure =>
            log.error(
              s"The action `${f.action.name}` for instance ${f.action.instance.metadata.name} failed with exit code '${f.exitCode}'. ${f.stdErr
                .getOrElse("")}"
            )
        }
        Future { result }
      }

  private def updateStatus(
      k8sConfig: Configuration
  )(implicit ec: ExecutionContext,
    system: ActorSystem,
    mat: ActorMaterializer,
    log: LoggingAdapter): Flow[ActionResult, ActionResult, NotUsed] =
    Flow[ActionResult]
      .mapAsync(1) { actionResult =>
        val client                                              = k8sInit(k8sConfig.setCurrentNamespace(actionResult.action.instance.metadata.namespace))
        implicit val statusSubEnabled: HasStatusSubresource[CR] = CustomResource.statusMethodsEnabler[CloudflowInstance.CR]

        val updatedInstance = actionResult.action.instance.copy(status = Some(actionResult.toStatus))
        log.info(s"Updating status for ${updatedInstance.metadata.name}")
        for {
          existingCr <- client.getOption[CloudflowInstance.CR](actionResult.action.instance.metadata.name)
          _ = existingCr
            .map(
              cloudflowInstanceCr =>
                CloudflowInstance.editor.updateMetadata(
                  updatedInstance,
                  updatedInstance.metadata.copy(resourceVersion = cloudflowInstanceCr.metadata.resourceVersion)
                )
            )
            .map(updateCr => client.updateStatus(updateCr))
        } yield actionResult
      }

  private def executeActions(
      executor: ActionExecutor
  )(implicit system: ActorSystem,
    materializer: Materializer,
    ec: ExecutionContext,
    log: LoggingAdapter,
    settings: Settings): Flow[Action, ActionResult, NotUsed] =
    Flow[Action]
      .mapAsync(1) {
        executor.execute
      }

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
          .watchWithOptions[O](options = options, bufsize = MaxResponseSize)
          .mapMaterializedValue(_ ⇒ NotUsed)
      )
      .recoverWithRetries(
        -1, {
          case _: TcpIdleTimeoutException ⇒
            watch[O](client)
          case e: skuber.api.client.K8SException ⇒
            println(s"""Ignoring Skuber K8SException (st  atus message: '${e.status.message.getOrElse("")}'.)""")
            watch[O](client)
          case _: Exception =>
            watch[O](client)
        }
      )
  }

  private def getCurrentEvents[O <: ObjectResource](
      client: KubernetesClient,
      options: ListOptions
  )(implicit lfmt: Format[ListResource[O]], rd: ResourceDefinition[O], ec: ExecutionContext): Future[List[WatchEvent[O]]] =
    for {
      namespaces ← client.getNamespaceNames
      lists      ← Future.sequence(namespaces.map(ns ⇒ client.usingNamespace(ns).listWithOptions[ListResource[O]](options)))
      watchEvents = lists.flatMap(_.items.map(item ⇒ WatchEvent(EventType.ADDED, item)))
    } yield watchEvents

  private def runStream(
      graph: RunnableGraph[Future[_]],
      unexpectedCompletionMsg: String,
      errorMsg: String
  )(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext, log: LoggingAdapter): Unit =
    graph.run.onComplete {
      case Success(_) ⇒
        log.warning(unexpectedCompletionMsg)
        system.registerOnTermination(exitWithFailure())
        system.terminate()

      case Failure(t) ⇒
        log.error(t, errorMsg)
        system.registerOnTermination(exitWithFailure())
        system.terminate()
    }

  private def exitWithFailure(): Unit = System.exit(-1)

}

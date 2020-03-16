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

package cloudflow.events.errors

import java.io.{ PrintWriter, StringWriter }
import java.security.MessageDigest
import java.time.Clock

import scala.concurrent.{ Await, ExecutionContextExecutor, Future }
import scala.concurrent.duration._
import cloudflow.streamlets.{ LoadedStreamlet, StreamletDefinition }
import org.slf4j.LoggerFactory
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import skuber.api.Configuration
import skuber.api.client.KubernetesClient
import skuber.json.format.eventFmt
import skuber.{ k8sInit, Event, ObjectEditor, ObjectMeta, ObjectReference }

import scala.concurrent.duration.Duration

/**
 * Create Kubernetes `skuber.Events` given a runtime exception
 *
 * TODO: This reuses some logic found in `cloudflow-operator`. We may want to DRY this up at some point.
 * - Building a `skuber.Event``
 * - Creating a resource with skuber
 * - Resource name creation and constraints
 */
object ErrorEvents {
  @transient private lazy val log = LoggerFactory.getLogger(getClass.getName)

  private val StreamletRuntimeErrorReason = "StreamletRuntimeError"
  private val ErrorEventType              = "Error"
  private val ErrorEventsEnabledDefault   = false
  private val IncludeStacktraceDefault    = true

  /**
   * Creating an `ActorSystem` here will possibly mean that we run two actor systems (depending on the runner), but
   * this will only occur when unhandled exceptions are raised. We experimented with passing the `ActorSystem`
   * from cloudflow.runner.Runner, to all runner types, and `ErrorEvents`, but this caused worse rippling changes
   * that required all streamlets to include an akka dependency when it might not be required.
   */
  private implicit lazy val system = ActorSystem("error_events")
  private implicit lazy val mat    = ActorMaterializer()
  private val md5                  = MessageDigest.getInstance("MD5")

  private[cloudflow] val OperatorSource = Event.Source(Some("cloudflow-streamlet"))

  private[cloudflow] implicit val eventEditor = new ObjectEditor[Event] {
    override def updateMetadata(obj: Event, newMetadata: ObjectMeta) = obj.copy(metadata = newMetadata)
  }

  /**
   * Report a K8s `skuber.Event` that represents this exception. When the exception occurs more than once
   * then we lookup the previous Event, increment its `count` and update its `lastTimestamp` fields. A hash of the
   * exception is included in the Event name so that we can detect when an Event already exists for the same streamlet
   * and exception.
   */
  def report(loadedStreamlet: LoadedStreamlet, config: Config, throwable: Throwable, timeout: Duration = 10 seconds): Unit = {
    val enabled = config.getOrElse[Boolean]("cloudflow.runner.error-events.enabled", ErrorEventsEnabledDefault)
    if (enabled) {
      implicit val ec: ExecutionContextExecutor = mat.executionContext
      val future = Future {
        val streamlet         = loadedStreamlet.config
        val runnerType        = loadedStreamlet.streamlet.runtime.name
        val podName           = config.getString("cloudflow.runner.pod.metadata.name")
        val podUid            = config.getString("cloudflow.runner.pod.metadata.uid")
        val podNamespace      = config.getString("cloudflow.runner.pod.metadata.namespace")
        val stacktraceEnabled = config.getOrElse[Boolean]("cloudflow.runner.error-events.include-stack-trace", IncludeStacktraceDefault)
        newEvent(streamlet, runnerType, throwable, podName, podUid, podNamespace, stacktraceEnabled)
      }.flatMap { event ⇒
          implicit val client: KubernetesClient = getK8sClient.usingNamespace(event.metadata.namespace)
          client.getOption(event.metadata.name).flatMap {
            case Some(evt) ⇒ client.update(evt.copy(count = evt.count.map(_ + 1), lastTimestamp = Some(now)))
            case _         ⇒ client.create(event)
          }
        }
        .map { event ⇒
          log.info(s"Created '$StreamletRuntimeErrorReason' Kubernetes Event with name '${event.metadata.name}'")
        }
        .recover {
          case ex ⇒ log.error(s"An error occurred while creating a '$StreamletRuntimeErrorReason' Kubernetes Event", ex)
        }
      Await.ready(future, timeout)
    } else {
      log.info(s"Error events configuration is disabled: 'cloudflow.runner.error-events.enabled: false'")
    }
  }

  private[cloudflow] def newEvent(streamlet: StreamletDefinition,
                                  runnerType: String,
                                  throwable: Throwable,
                                  podName: String,
                                  podUid: String,
                                  podNamespace: String,
                                  stacktraceEnabled: Boolean): skuber.Event = {
    val appId         = streamlet.appId
    val streamletName = streamlet.streamletRef

    val objectReference = ObjectReference(
      kind = "Pod",
      name = podName,
      uid = podUid,
      namespace = podNamespace
    )

    val eventTime             = now
    val messageWithStacktrace = exceptionToString(throwable)
    val message               = if (stacktraceEnabled) messageWithStacktrace else throwable.toString
    val metadataName          = name(podName, messageWithStacktrace)

    Event(
      metadata = ObjectMeta(
        name = metadataName,
        namespace = podNamespace,
        labels = CloudflowLabels(streamlet).baseLabels ++ Map(
                "com.lightbend.cloudflow/app-id"         -> appId,
                "com.lightbend.cloudflow/streamlet-name" -> streamletName,
                "com.lightbend.cloudflow/runner-type"    -> runnerType
              )
      ),
      involvedObject = objectReference,
      reason = Some(StreamletRuntimeErrorReason),
      message = Some(message),
      `type` = Some(ErrorEventType),
      firstTimestamp = Some(eventTime),
      lastTimestamp = Some(eventTime),
      count = Some(1),
      source = Some(OperatorSource)
    )
  }

  /**
   * Transform an exception message and stacktrace into a string.
   */
  private def exceptionToString(throwable: Throwable): String = {
    val sw = new StringWriter
    val pw = new PrintWriter(sw)
    throwable.printStackTrace(pw)
    sw.toString
  }

  /**
   * Generate an Event name using the source pod metadata.name and a hash of the exception message and stack trace. The
   * name will never exceed 63 characters. The pod metadata.name will be truncated if necessary.
   */
  private[cloudflow] def name(source: String, message: String): String = {
    val messageHash = hash(message)
    "%.52s-%.10s".format(source, messageHash)
  }

  /**
   * Create a 10 character, lowercase, hex, MD5 hash prefix.
   */
  private[cloudflow] def hash(value: String): String =
    md5
      .digest(value.getBytes) // calling digest implicitly resets MD5 instance
      .take(5)
      .map("%02X".format(_))
      .mkString
      .toLowerCase

  // visibility for testing
  private[cloudflow] var k8sClient: Option[KubernetesClient] = None
  private def getK8sClient(implicit system: ActorSystem, mat: ActorMaterializer): KubernetesClient =
    k8sClient.getOrElse(k8sInit(Configuration.defaultK8sConfig))

  // visibility for testing
  private[cloudflow] var clock = Clock.systemUTC()
  private def now              = clock.instant().atZone(clock.getZone)
}

/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
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

import java.time.{ Clock, Instant, ZoneId }

import scala.concurrent.Future
import cloudflow.streamlets.{ LoadedStreamlet, Streamlet, StreamletDefinition, StreamletRuntime }
import com.typesafe.config.ConfigFactory
import org.scalatest.{ FreeSpec, MustMatchers, OptionValues }
import skuber.{ ObjectMeta, ObjectReference }
import skuber.api.client.KubernetesClient
import org.mockito.MockitoSugar
import org.mockito.ArgumentMatchersSugar
import org.mockito.captor.ArgCaptor
import org.scalatest.concurrent.ScalaFutures

class ErrorEventsSpec extends FreeSpec
  with MustMatchers
  with ScalaFutures
  with OptionValues
  with MockitoSugar
  with ArgumentMatchersSugar {

  "ErrorEvents" - {
    val podName = "test-app-test-streamlet-6d9fd8f44-kgvkp"
    val podNamespace = "test-app"
    val podUid = "9d74877d-d570-11e9-8a34-42010aa80061"
    val runnerType = "akka"

    val exception = new Exception("Runtime Error")

    val runnerConfig = ConfigFactory.parseString(
      s"""
         |cloudflow.runner.pod.metadata: {
         |  name:       "$podName"
         |  namespace:  "$podNamespace"
         |  uid:        "$podUid"
         |}
         |cloudflow.runner.error-events: {
         |  enabled:              true
         |  include-stack-trace:  true
         |}
         |""".stripMargin)

    val config = ConfigFactory.load("config-map-sample.json")
    val streamletDef = StreamletDefinition.read(config).get
    val streamlet = mock[Streamlet]
    when(streamlet.runtime).thenAnswer(new StreamletRuntime { def name = "akka" })
    val loadedStreamlet = LoadedStreamlet(streamlet, streamletDef)

    "create a K8s event" in {
      ErrorEvents.clock = resetClock()
      val eventTime = ErrorEvents.clock.instant().atZone(ErrorEvents.clock.getZone)

      val client = mockClient()
      ErrorEvents.k8sClient = Some(client)

      val captor = ArgCaptor[skuber.Event]
      ErrorEvents.report(loadedStreamlet, runnerConfig, exception)

      verify(client).create[skuber.Event](captor)(any, any, any)
      val event = captor.value

      event.metadata.namespace mustBe podNamespace
      event.involvedObject.name mustBe podName
      event.involvedObject.namespace mustBe podNamespace
      event.firstTimestamp.value mustBe eventTime
      event.lastTimestamp.value mustBe eventTime
      event.count.value mustBe 1
      event.message.get must startWith("java.lang.Exception: Runtime Error")
    }

    "update a K8s event" in {
      ErrorEvents.clock = resetClock()
      val eventTime = ErrorEvents.clock.instant().atZone(ErrorEvents.clock.getZone)

      // move clock forward 10 seconds
      ErrorEvents.clock = Clock.offset(ErrorEvents.clock, java.time.Duration.ofSeconds(10))
      val updatedEventTime = ErrorEvents.clock.instant().atZone(ErrorEvents.clock.getZone)

      val client = mockClient()
      ErrorEvents.k8sClient = Some(client)

      def existingEvent(name: String) = skuber.Event(
        metadata = ObjectMeta(
          name = name
        ),
        involvedObject = ObjectReference(),
        count = Some(1),
        firstTimestamp = Some(eventTime),
        lastTimestamp = Some(eventTime)
      )

      when(client.getOption[skuber.Event](any)(any, any, any))
        .thenAnswer[String](name ⇒ Future.successful(Some(existingEvent(name))))

      val eventCaptor = ArgCaptor[skuber.Event]
      val nameCaptor = ArgCaptor[String]

      ErrorEvents.report(loadedStreamlet, runnerConfig, exception)

      verify(client).update[skuber.Event](eventCaptor)(any, any, any)
      verify(client).getOption[skuber.Event](nameCaptor)(any, any, any)

      val event = eventCaptor.value

      event.metadata.name mustBe nameCaptor.value
      event.count.value mustBe 2
      event.firstTimestamp.value mustBe eventTime
      event.lastTimestamp.value mustBe updatedEventTime
    }

    "may be disabled" in {
      val disabled = ConfigFactory.parseString("cloudflow.runner.error-events.enabled: false")
      val newConfig = disabled.withFallback(runnerConfig)

      val client = mockClient()
      ErrorEvents.k8sClient = Some(client)

      ErrorEvents.report(loadedStreamlet, newConfig, exception)

      // verify the K8s client was never called
      verifyZeroInteractions(client)
    }

    "include a full stack trace" in {
      val message = ErrorEvents.newEvent(streamletDef, runnerType, exception, podName, podUid, podNamespace, stacktraceEnabled = true).message.get

      message must startWith("java.lang.Exception: Runtime Error")
      message.split("\n").length > 0
    }

    "not include a full stack trace" in {
      val message = ErrorEvents.newEvent(streamletDef, runnerType, exception, podName, podUid, podNamespace, stacktraceEnabled = false).message.get

      message mustBe "java.lang.Exception: Runtime Error"
    }

    "hash is lowercase hex and 10 chars long" in {
      assert("^[a-f0-9]{10}$".r.findFirstMatchIn(ErrorEvents.hash("foobar")).nonEmpty)
    }

    "name is 63 characters or less" in {
      val source = "test-app-test-streamlet-with-a-very-long-name-that-will-get-truncated-6d9fd8f44-kgvkp"
      val message = "An error message with a stack trace\nand\nlots\nof\nlines\n"
      assert(ErrorEvents.name(source, message).length() <= 63)
    }
  }

  def resetClock() = Clock.fixed(Instant.ofEpochMilli(0), ZoneId.systemDefault())

  def mockClient() = {
    val client = mock[KubernetesClient]
    // default mocks
    when(client.usingNamespace(any))
      .thenAnswer(client)
    when(client.getOption[skuber.Event](any)(any, any, any))
      .thenAnswer(Future.successful(None))
    when(client.create[skuber.Event](any)(any, any, any))
      .thenAnswer[skuber.Event](Future.successful)
    when(client.update[skuber.Event](any)(any, any, any))
      .thenAnswer[skuber.Event](Future.successful)
    client
  }
}

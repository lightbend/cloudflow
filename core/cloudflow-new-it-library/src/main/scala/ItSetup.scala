/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

import java.io.File
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.Using
import akka.cli.cloudflow.commands
import akka.cli.cloudflow.models.ApplicationStatus
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.PodResource
import org.scalatest.AppendedClues
import org.scalatest.Informing
import org.scalatest.Notifying
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should._
import org.slf4j.LoggerFactory

object ItSetup {

  private var lastClient = new DefaultKubernetesClient()
  def client: DefaultKubernetesClient = {
    if (lastClient.getHttpClient.connectionPool().connectionCount() <= 0) {
      lastClient = new DefaultKubernetesClient()
      lastClient
    } else {
      lastClient
    }
  }

  private var lastCli = new TestingCli(client)
  def cli: TestingCli = {
    if (client != lastCli.client) {
      lastCli = new TestingCli(client)
      lastCli
    } else {
      lastCli
    }
  }

}

trait ItSetup {
  self: Eventually with Matchers with AppendedClues with Informing with Notifying =>
  import ItSetup._

  val appName: String

  val crFile: File

  val resource = new ItResources {}

  val logger = LoggerFactory.getLogger(this.getClass)

  def precond(test: Boolean, message: String) = if (!test) new AssertionError(message)

  def assumeSuccess[A](tryA: Try[A]): A = tryA.fold(ex => cancel(s"Failure: ${ex.getMessage()}"), identity)

  def assertSuccess[A](tryA: Try[A]): A = tryA.fold(ex => fail(s"Failure: ${ex.getMessage()}"), identity)

  def assertFailure[A](tryA: Try[A]): Throwable = tryA.fold(identity, a => fail(s"Success: ${a}"))

  def assumeClusterExists(): Unit = {
    assumeSuccess(cli.run(commands.List())).withClue("Cluster not available or Cloudflow not installed.")
  }

  def assumeAppListed(): Unit = eventually {
    val res = cli.run(commands.List())
    assumeSuccess(res).withClue("App not listed")
    assume(res.get.summaries.size == 1, "Expected only 1 app")
    assume(res.get.summaries.head.name == appName, "Wrong app name")
  }

  def assumeAppRunning(): ApplicationStatus = eventually {
    val res = cli.run(commands.Status(appName))
    assumeSuccess(res).withClue("Status command failed")
    assume(res.get.status.summary.name == appName, "Wrong app name")
    assume(res.get.status.status == "Running", "App not running")
    res.get.status
  }

  def deployApp(): Unit = {
    withK8s { k8s =>
      Try {
        k8s.namespaces().create(resource.namespace(appName))
      }
    }
    val res = cli.run(commands.Deploy(crFile = crFile))
    assertSuccess(res).withClue("Deploy command failed")
    assumeAppListed()
    assumeAppRunning()
  }

  def safeCleanup(namespace: String) = {
    logger.debug("Performing a safe cleanup (might take time)")
    withK8s { k8s =>
      logger.debug("deleting pvcs")
      k8s.persistentVolumeClaims().inNamespace(namespace).delete()
      eventually {
        assert {
          k8s.persistentVolumeClaims().inNamespace(namespace).list().getItems.isEmpty
        }
      }
      logger.debug("deleting service accounts")
      k8s.serviceAccounts().inNamespace(namespace).delete()
      logger.debug("deleting secrets")
      k8s.secrets().inNamespace(namespace).delete()
      logger.debug("deleting namespace")
      k8s.namespaces().list().getItems.asScala.find(_.getMetadata.getName == namespace).map { ns =>
        k8s.namespaces().delete(ns)
      }
      eventually {
        assert {
          k8s
            .namespaces()
            .list()
            .getItems
            .asScala
            .find(_.getMetadata.getName == namespace)
            .map { ns =>
              logger.debug(
                s"namespace ${ns.getMetadata.getName} found: finalizers: ${ns.getMetadata.getFinalizers} phase: ${ns.getStatus.getPhase}")
            }
            .isEmpty
        }
      }
    }
  }

  def undeployApp(failIfNotPresent: Boolean = true): Unit = {
    logger.debug(s"Undeploying $appName")
    val exists = !cli.run(commands.List()).get.summaries.isEmpty
    if (!exists && failIfNotPresent) {
      fail(s"$appName doesn't exists.")
    } else if (!exists) {
      logger.debug("App already undeployed, cleaning up")
      // Already undeployed
      safeCleanup(appName)
    } else {
      logger.debug(s"Undeploying app $appName")
      cli.run(commands.Undeploy(appName))
      eventually {
        val list = cli.run(commands.List()).get
        if (!list.summaries.isEmpty) {
          fail(s"$appName not undeployed.")
        }
      }
      logger.debug("cleaning up")
      safeCleanup(appName)
    }
  }

  def withRunningApp[A](action: ApplicationStatus => A): A =
    action(assumeAppRunning())

  def assertRunningApp(): ApplicationStatus = {
    val res = cli.run(commands.Status(appName))
    assertSuccess(res).withClue(s"Status command failed (app: $appName).")
    val status = res.get.status
    val summary = status.summary
    assert(summary.name == appName, s"Expected app: $appName, got: ${summary.name}.")
    assert(status.status == "Running", s"App $appName should be Running, it is ${status.status}.")
    status
  }

  /**
   * Run an action, then wait for app to be Ready.
   */
  def configureApp[A](wait: FiniteDuration = resource.postConfigurationTimeout)(
      action: ApplicationStatus => Try[A]): A =
    withRunningApp { status =>
      val res = action(status)
      assertSuccess(res).withClue("configuration action failed")
      var consecutiveRunning = 0
      eventually(timeout(wait)) {
        assertRunningApp()
        consecutiveRunning += 1
        assert(consecutiveRunning > 5)
        res.get
      }
    }

  def configureAppExpectFail[A](wait: FiniteDuration = resource.postConfigurationTimeout)(
      action: ApplicationStatus => Try[A]): Unit =
    withRunningApp { status =>
      val res = action(status)
      assertFailure(res).withClue("configuration action should have failed")
      var consecutiveRunning = 0
      eventually(timeout(wait)) {
        assertRunningApp()
        consecutiveRunning += 1
        assert(consecutiveRunning > 5)
      }
    }

  def withK8s[A](action: KubernetesClient => A): A =
    Using(client)(action).get

  def withStreamletPod[A](status: ApplicationStatus, streamletName: String)(action: PodResource[Pod] => A): A = {
    val streamlet = status.streamletsStatuses.find(s => s.name == streamletName)
    val podName = streamlet.get.podsStatuses.head.name
    withK8s { k8s => action(k8s.pods().inNamespace(status.summary.namespace).withName(podName)) }
  }

  def streamletPodLog(status: ApplicationStatus, streamletName: String) =
    withStreamletPod(status, streamletName) { _.getLog() }
}

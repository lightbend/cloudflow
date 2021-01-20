/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

import akka.cli.cloudflow._

import java.io.File

import org.scalatest._
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should._
import org.scalatest.freespec.AnyFreeSpec

trait ItSpec extends AnyFreeSpec with Matchers with TryValues with Eventually with AppendedClues with ItSetup {
  override implicit lazy val patienceConfig: PatienceConfig = PatienceConfig(resource.patience, resource.interval)
}

class ItDefaultSpec(name: String) extends ItSpec with BeforeAndAfterAll {
  val appName: String = name

  val crFile: File = new File(s"target/${name}.json")

  override def beforeAll() = {
    Setup.init()
    logger.debug("Init done")
    assumeClusterExists()
    logger.debug("Cluster exists, going to cleanup")
    undeployApp(failIfNotPresent = false)
    logger.debug("Deploying the application")
    deployApp()
  }

  override def afterAll() = {
    undeployApp()
  }
}

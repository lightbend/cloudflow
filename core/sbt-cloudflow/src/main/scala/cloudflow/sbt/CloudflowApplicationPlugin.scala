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

package cloudflow.sbt

import java.util.concurrent.atomic.AtomicInteger

import com.lightbend.sbt.javaagent.JavaAgent
import com.lightbend.sbt.javaagent.JavaAgent.JavaAgentKeys.javaAgents
import cloudflow.blueprint.deployment.ApplicationDescriptor
import cloudflow.sbt.CloudflowKeys._
import sbt.Keys._
import sbt.{ Def, _ }

import scala.util.control.NoStackTrace

/**
 * SBT Plugin for publishing multi-module projects using a combination of different runtimes and streamlet libraries.
 * This plugin enables the creation of a single docker image that contains all the required dependencies for the application.
 * It also verifies the blueprint and publishes it to the platform after a successful build of the docker image.
 */
object CloudflowApplicationPlugin extends AutoPlugin {

  val PrometheusAgent: ModuleID = "io.prometheus.jmx" % "jmx_prometheus_javaagent" % "0.11.0"

  private val cloudflowAppProjects: AtomicInteger = new AtomicInteger()

  /** This plugin depends on these other plugins: */
  override def requires: Plugins =
    CommonSettingsAndTasksPlugin && BlueprintVerificationPlugin && ImagePlugin

  override def buildSettings = Seq(
    cloudflowDockerRegistry := None,
    cloudflowDockerRepository := None
  )

  /** Set default values for keys. */
  override def projectSettings = Seq(
    blueprint := None,
    runLocalConfigFile := None,
    packageOptions in (Compile, packageBin) +=
        Package.ManifestAttributes(new java.util.jar.Attributes.Name("Blueprint") -> blueprintFile.value.getName),
    javaAgents += JavaAgent(
          module = PrometheusAgent,
          name = ApplicationDescriptor.PrometheusAgentKey,
          scope = JavaAgent.AgentScope(compile = false, test = false, run = false, dist = true),
          arguments = "${PROMETHEUS_JMX_AGENT_PORT}:${PROMETHEUS_JMX_AGENT_CONFIG_PATH}"
        ),
    verifyBlueprint := verifyBlueprint.dependsOn(checkUsageCount()).andFinally(resetCount()).value
  )

  /**
   * Check that this plugin isn't defined more than once in the multi-project build.
   */
  private def checkUsageCount(): Def.Initialize[Task[Unit]] = Def.task {
    val isPipelineApp = thisProject.value.autoPlugins.exists(_.label.equals(CloudflowApplicationPlugin.label))
    if (isPipelineApp && cloudflowAppProjects.incrementAndGet() > 1) {
      throw new MultipleCloudflowApplicationError(
        "You can only define one project as a Cloudflow Application in a multi-project sbt build."
      )
    }
  }

  private def resetCount() =
    cloudflowAppProjects.set(0)
}

class MultipleCloudflowApplicationError(msg: String) extends Exception(s"\n$msg") with NoStackTrace with sbt.FeedbackProvidedException

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

import java.io._

import sbt._

import cloudflow.sbt.CloudflowKeys._
import cloudflow.blueprint.deployment.CloudflowApplication

/**
 */
object CRGenerationPlugin extends AutoPlugin {
  final val TEMP_DIRECTORY = new File(System.getProperty("java.io.tmpdir"))

  override def requires = CommonSettingsAndTasksPlugin && StreamletScannerPlugin

  override def projectSettings = Seq(
    cloudflowApplicationSpec := generateCR.value
  )

  private[sbt] def generateCR: Def.Initialize[Task[CloudflowApplication.Spec]] = Def.task {
    val appDescriptor = applicationDescriptor.value.get
    import appDescriptor._
    val s = CloudflowApplication.Spec(appId, appVersion, streamlets, connections, deployments, agentPaths)
    println(s"Spec = $s")
    s
  }
}

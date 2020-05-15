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

package cloudflow.streamlets

import java.lang.management.ManagementFactory
import collection.JavaConverters._

import com.typesafe.config._

object BootstrapInfo {
  private def getGCInfo: List[(String, javax.management.ObjectName)] = {
    val gcMxBeans = ManagementFactory.getGarbageCollectorMXBeans()
    gcMxBeans.asScala.map(b ⇒ (b.getName, b.getObjectName)).toList
  }

  def box(str: String): String =
    if ((str == null) || (str.isEmpty)) ""
    else {
      val line = s"""+${"-" * 80}+"""
      s"$line\n$str\n$line"
    }

  private def prettyPrintConfig(c: Config): String =
    c.root
      .render(
        ConfigRenderOptions
          .concise()
          .setFormatted(true)
          .setJson(false)
      )

  private def getJVMRuntimeParameters: String = {
    val runtime = Runtime.getRuntime
    import runtime._

    s"""
     |Available processors    : $availableProcessors
     |Free Memory in the JVM  : $freeMemory
     |Max Memory JVM can use  : $maxMemory
     |Total Memory in the JVM : $maxMemory
    """.stripMargin
  }

  // TODO move this, this is Akka specific?
  def startRunnerMessage(blockingIODispatcherConfig: Config,
                         dispatcherConfig: Config,
                         deploymentConfig: Config,
                         streamletConfig: Config): String =
    s"""
      |\n${box("JVM Resources")}
      |${getJVMRuntimeParameters}
      |\n${box("Akka Deployment Config")}
      |\n${prettyPrintConfig(deploymentConfig)}
      |\n${box("Akka Default Blocking IO Dispatcher Config")}
      |\n${prettyPrintConfig(blockingIODispatcherConfig)}
      |\n${box("Akka Default Dispatcher Config")}
      |\n${prettyPrintConfig(dispatcherConfig)}
      |\n${box("Streamlet Config")}
      |\n${prettyPrintConfig(streamletConfig)}
      |\n${box("GC Type")}
      |\n${getGCInfo}
      """.stripMargin
}

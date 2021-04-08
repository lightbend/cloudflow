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

package cloudflow.blueprint

import com.typesafe.config.{ Config, ConfigFactory }

import scala.io.Source
import scala.util.{ Failure, Success, Try }

object RunnerConfigUtils {
  val StorageMountPathKey = "storage.mountPath"
  val MetadataName        = "metadata.name"
  val MetadataNamespace   = "metadata.namespace"
  val MetadataUid         = "metadata.uid"

  def addStorageConfig(config: Config, pvcVolumeMountPath: String): Config = {
    val storageConfig = ConfigFactory.parseString(s"""$StorageMountPathKey:"$pvcVolumeMountPath"""")
    config.withFallback(storageConfig)
  }

  def addPodRuntimeConfig(config: Config, downwardApiVolumeMountPath: String): Config = {
    val (name, namespace, uid) = getPodMetadata(downwardApiVolumeMountPath)
    val podRuntimeConfig       = ConfigFactory.parseString(s"""
                                                              |cloudflow.runner.pod: {
                                                              |  $MetadataName:"$name"
                                                              |  $MetadataNamespace:"$namespace"
                                                              |  $MetadataUid:"$uid"
                                                              |}
                                                              |""".stripMargin)
    config.withFallback(podRuntimeConfig)
  }

  def getPodMetadata(downwardApiVolumeMountPath: String): (String, String, String) = {
    val name      = readDownwardApi(downwardApiVolumeMountPath, MetadataName)
    val namespace = readDownwardApi(downwardApiVolumeMountPath, MetadataNamespace)
    val uid       = readDownwardApi(downwardApiVolumeMountPath, MetadataUid)
    (name, namespace, uid)
  }

  private def readDownwardApi(downwardApiVolumeMountPath: String, filename: String): String = {
    val path = s"$downwardApiVolumeMountPath/$filename"
    Try(Source.fromFile(path).getLines().mkString) match {
      case Success(contents) => contents
      case Failure(ex) =>
        throw new Exception(s"An error occurred while attempting to access the downward API volume mount with path '$path'", ex)
    }
  }
}

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

import sbt.Keys._
import sbt._

import cloudflow.sbt.CloudflowKeys._

/**
 * SBT Plugin for publishing multi-module projects using a combination of different runtimes and streamlet libraries.
 * It also verifies the blueprint and publishes it to the platform after a successful build of the docker image.
 */
object CloudflowApplicationPlugin extends AutoPlugin {

  /** This plugin depends on these other plugins: */
  override def requires: Plugins = StreamletDescriptorsPlugin && BlueprintVerificationPlugin && BuildAppPlugin

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
    verifyBlueprint := verifyBlueprint.value,
    buildApp := cloudflowApplicationCR.value
  )
}

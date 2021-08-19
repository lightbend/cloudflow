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

package cloudflow.sbt

import sbt._
import sbt.Keys._

import cloudflow.sbt.CloudflowKeys.cloudflowVersion

/**
 * SBT Plugin for building generic libraries that use Cloudflow concepts, such as data definitions (e.g. `Codec`),
 * AVRO support, etc.
 *
 * This library is assumed to not contain any runtime-specific streamlet implementation.
 */
object CloudflowLibraryPlugin extends AutoPlugin {

  /** This plugin depends on these other plugins: */
  override def requires: Plugins = CommonSettingsAndTasksPlugin

  /** Set default values for keys. */
  override def projectSettings =
    Seq(libraryDependencies ++= Vector(
        "com.lightbend.cloudflow" % s"cloudflow-streamlets_${(ThisProject / scalaBinaryVersion).value}" % (ThisProject / cloudflowVersion).value))

}

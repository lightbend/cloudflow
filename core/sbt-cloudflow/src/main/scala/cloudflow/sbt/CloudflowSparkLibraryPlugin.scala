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

import sbt._
import sbt.Keys._

/**
 * SBT Plugin for building Cloudflow streamlet libraries that depend on the "spark" runtime.
 */
object CloudflowSparkLibraryPlugin extends AutoPlugin {
  /** This plugin depends on these other plugins: */
  override def requires: Plugins = CommonSettingsAndTasksPlugin

  /** Set default values for keys. */
  override def projectSettings = Seq(
    libraryDependencies ++= Vector(
      "com.lightbend.cloudflow" % "cloudflow-runner" % BuildInfo.version,
      "com.lightbend.cloudflow" %% "cloudflow-spark" % BuildInfo.version,
      "com.lightbend.cloudflow" %% "cloudflow-spark-testkit" % BuildInfo.version % "test"
    ),
    dependencyOverrides += "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.9.9",
    dependencyOverrides += "com.fasterxml.jackson.core"    % "jackson-databind"     % "2.9.9",
    javaOptions in com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport.Universal ++= Seq( // this is for local experimentation - do not remove
    // -J params will be added as jvm parameters
    // "-J-Xmx1536m",
    //"-J-Xms1536m"
    )
  )
}

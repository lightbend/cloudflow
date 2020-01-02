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

import cloudflow.sbt.CloudflowKeys.cloudflowBuildNumber
import sbt._

import scala.util.Try

final case class BuildNumber(private val _buildNumber: String, val hasUncommittedChanges: Boolean) {
  val buildNumber: String = if (hasUncommittedChanges) s"${_buildNumber}-dirty" else _buildNumber
}

object BuildNumberPlugin extends AutoPlugin {
  override def projectSettings = Seq(
    cloudflowBuildNumber := generateBuildNumber
  )

  private def generateBuildNumber: BuildNumber = {
    import scala.sys.process._

    if (Try("git --version".!!).isSuccess) {
      if (Try("git rev-parse --git-dir".!!).isSuccess) {
        val isClean = "git diff --quiet --ignore-submodules HEAD".! == 0
        val commits = "git rev-list --count HEAD".!!.trim()
        val hash = "git rev-parse --short HEAD".!!.trim()
        val build = s"${commits}-${hash}"

        BuildNumber(build, !isClean)
      } else {
        sys.error("The current project is not a valid Git project.")
      }

    } else {
      sys.error("Git is not installed or cannot be found on this machine.")
    }
  }
}

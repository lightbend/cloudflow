/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.buildtool

import java.nio.file._
import java.io._
import cloudflow.blueprint.deployment._

case class RuntimeDescriptor(
    id: String,
    appDescriptor: ApplicationDescriptor,
    appDescriptorFile: Path,
    outputFile: File,
    logConfig: Path,
    localConfPath: Option[String])

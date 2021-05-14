package com.lightbend.cloudflow.buildtool

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

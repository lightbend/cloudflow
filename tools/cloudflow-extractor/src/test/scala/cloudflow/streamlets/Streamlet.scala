/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.streamlets

case class ConfigParameter(key: String, description: String)

abstract class Streamlet {
  def configParameters: Vector[ConfigParameter] = Vector()
  final def jsonDescriptor: String =
    s"{ config_parameters: [${configParameters.map(cp => s"{ key: ${cp.key}, description: ${cp.description} }").mkString(",")}] }"
}

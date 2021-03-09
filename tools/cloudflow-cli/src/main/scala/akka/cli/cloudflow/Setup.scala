/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow

import akka.cloudflow.Json
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import io.fabric8.kubernetes.client.utils.Serialization

object Setup {

  def init() = {
    val mapper = Json.mapper
    // REMIND ME: turn it on in development
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
  }

}

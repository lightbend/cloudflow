/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import io.fabric8.kubernetes.client.utils.Serialization

object Setup {

  def init() = {
    Serialization.jsonMapper().registerModule(DefaultScalaModule)
    // doublecheck if needed
    Serialization
      .jsonMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
  }

}

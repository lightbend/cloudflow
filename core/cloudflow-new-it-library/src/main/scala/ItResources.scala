/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

import org.scalatest.time.SpanSugar._

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.NamespaceBuilder

trait ItResources {
  val deploySleep = 10.seconds
  val postConfigurationTimeout = 2.minutes
  val patience = 5.minutes
  val interval = 2.seconds
  def namespace(name: String) =
    new NamespaceBuilder().withMetadata(new ObjectMetaBuilder().withName(name).build()).build()
}

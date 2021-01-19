/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow

import scala.collection.immutable
import java.net.URL

import com.fasterxml.jackson.annotation.JsonCreator

object models {

  @JsonCreator
  final case class CRSummary(name: String, namespace: String, version: String, creationTime: String)

  @JsonCreator
  final case class ContainersReady(ready: Int, total: Int)

  @JsonCreator
  final case class PodStatus(name: String, ready: ContainersReady, status: String, restarts: Int)

  @JsonCreator
  final case class EndpointStatus(name: String, url: URL)

  @JsonCreator
  final case class StreamletStatus(name: String, podsStatuses: immutable.Seq[PodStatus])

  @JsonCreator
  final case class ApplicationStatus(
      summary: CRSummary,
      status: String,
      endpointsStatuses: immutable.Seq[EndpointStatus],
      streamletsStatuses: immutable.Seq[StreamletStatus])

}

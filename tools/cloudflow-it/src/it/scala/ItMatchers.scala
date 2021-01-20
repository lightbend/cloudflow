/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

import akka.cli.cloudflow.models.ApplicationStatus
import org.scalatest.matchers._

trait ItMatchers {
  def containStreamlet(streamletName: String): Matcher[ApplicationStatus] = status => {
    val streamlets = status.streamletsStatuses
    MatchResult(
      streamlets.exists(s => s.name == streamletName),
      s"Streamlet not found: $streamletName",
      s"Found streamlet: $streamletName")
  }

}

object ItMatchers extends ItMatchers

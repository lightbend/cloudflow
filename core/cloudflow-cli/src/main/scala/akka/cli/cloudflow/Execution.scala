/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow

import scala.util.Try

trait Execution[T] {
  def run(): Try[T]
}

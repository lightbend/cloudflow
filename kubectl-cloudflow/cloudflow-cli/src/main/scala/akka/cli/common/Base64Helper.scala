/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.common

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

object Base64Helper {

  def encode(str: String): String =
    new String(Base64.getEncoder.encode(str.getBytes(UTF_8)), UTF_8)

  def decode(base64Str: String): String =
    new String(Base64.getDecoder.decode(base64Str.getBytes(UTF_8)), UTF_8)
}

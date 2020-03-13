/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.streamlets.proto

import java.security.MessageDigest
import java.util.Base64
import scalapb.descriptors.Descriptor
import cloudflow.streamlets.SchemaDefinition

object ProtoUtil {
  val Format = "proto"

  def createSchemaDefinition(descriptor: Descriptor) = SchemaDefinition(
    name = descriptor.fullName,
    schema = descriptor.asProto.toProtoString,
    fingerprint = fingerprintSha256(descriptor),
    format = Format
  )

  private def fingerprintSha256(descriptor: Descriptor): String =
    Base64
      .getEncoder()
      .encodeToString(MessageDigest.getInstance("SHA-256").digest(descriptor.asProto.toProtoString.getBytes("UTF-8")))
}

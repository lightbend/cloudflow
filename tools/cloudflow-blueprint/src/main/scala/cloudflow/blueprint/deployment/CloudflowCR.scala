/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
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

package cloudflow.blueprint.deployment

import spray.json._
import ApplicationDescriptorJsonFormat._

case class Metadata(annotations: Map[String, String], labels: Map[String, String], name: String)
case class CloudflowCR(apiVersion: String, kind: String, metadata: Metadata, spec: ApplicationDescriptor)

object CloudflowCRFormat extends CloudflowCRFormat
trait CloudflowCRFormat extends DefaultJsonProtocol {
  implicit val metadataFormat    = jsonFormat3(Metadata.apply)
  implicit val cloudflowCRFormat = jsonFormat4(CloudflowCR.apply)
}

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

package cloudflow.operator.action

import io.fabric8.kubernetes.api.model.Quantity

final case class Resources(request: String, limit: String)

sealed trait RunnerDefaults {}

final case class AkkaRunnerDefaults(resourceConstraints: ResourceConstraints, javaOptions: String)
    extends RunnerDefaults

final case class ResourceConstraints(
    cpuRequests: Quantity,
    memoryRequests: Quantity,
    cpuLimits: Option[Quantity],
    memoryLimits: Option[Quantity])

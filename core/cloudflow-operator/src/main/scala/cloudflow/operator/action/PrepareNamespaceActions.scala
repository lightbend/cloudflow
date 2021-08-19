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

import akka.datap.crd.App
import akka.kube.actions.Action
import cloudflow.operator.action.runner.Runner
import io.fabric8.kubernetes.api.model.OwnerReference

import scala.collection.immutable._

/**
 * Creates a sequence of resource actions for preparing the namespace where the application is
 * installed
 */
object PrepareNamespaceActions {
  def apply(
      app: App.Cr,
      runners: Map[String, Runner[_]],
      labels: CloudflowLabels,
      ownerReferences: List[OwnerReference]): Seq[Action] =
    app.spec.streamlets
      .map(streamlet => streamlet.descriptor.runtime)
      .distinct
      .flatMap { runtime =>
        runners.get(runtime).map(_.prepareNamespaceActions(app, labels, ownerReferences))
      }
      .flatten
}

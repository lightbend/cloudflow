/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
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

package cloudflow.operator

import org.scalatest.{ ConfigMap ⇒ _, _ }

import play.api.libs.json.Json
import cloudflow.blueprint._
import BlueprintBuilder._

class CloudflowApplicationSpec extends WordSpec
  with MustMatchers
  with GivenWhenThen
  with EitherValues
  with Inspectors
  with TestDeploymentContext {

  case class Foo(name: String)
  case class Bar(name: String)

  "CloudflowApplication.CR" should {
    "convert to Json and back" in {
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
      val egress = randomStreamlet().asEgress[Foo].withServerAttribute

      val ingressRef = ingress.ref("ingress")
      val egressRef = egress.ref("egress")

      val verifiedBlueprint = Blueprint()
        .define(Vector(ingress, egress))
        .use(ingressRef)
        .use(egressRef)
        .connect(ingressRef.out, egressRef.in)
        .verified.right.value

      val appId = "def-jux-12345"
      val appVersion = "42-abcdef0"
      val agentPaths = Map("prometheus" -> "/app/prometheus/prometheus.jar")

      val newApp = CloudflowApplicationSpecBuilder.create(appId, appVersion, verifiedBlueprint, agentPaths)
      val cr = CloudflowApplication(newApp)
      val customResource = Json.fromJson[CloudflowApplication.CR](Json.toJson(cr)).asEither.right.value
      customResource.spec mustBe cr.spec
    }
  }
}

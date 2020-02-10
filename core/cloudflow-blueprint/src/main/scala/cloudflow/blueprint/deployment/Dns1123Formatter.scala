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

package cloudflow.blueprint.deployment

import java.text.Normalizer

/*
  Here follows the formatting rules for k8s
  https://github.com/kubernetes/community/blob/master/contributors/design-proposals/architecture/identifiers.md#identifiers-and-names-in-kubernetes

  - Resource names are considered subdomains in RFC 1123 terminology

  - Namespaces names are an exception to the rule, they are considered labels
 */

object Dns1123Formatter {

  /**
   * Removes from the leading and trailing positions the specified characters.
   */
  private def trim(name: String): String =
    name.stripPrefix(".").stripPrefix("-").stripSuffix(".").stripSuffix("-")

  private def normalize(name: String): String =
    Normalizer
      .normalize(name, Normalizer.Form.NFKD)
      .toLowerCase
      .replace('_', '-')
      .replace('.', '-')
      .replaceAll("[^-a-z0-9]", "")

  /**
   * Make a name compatible with DNS 1123 Label
   * Limit the resulting name to 63 characters
   */
  def transformToDNS1123Label(name: String): String = {
    val labelMaxLength = 63
    trim(normalize(name).take(labelMaxLength))
  }

  /**
   * Make a name compatible with DNS 1123 Subdomain
   * Limit the resulting name to 253 characters
   */
  def transformToDNS1123SubDomain(name: String): String = {
    val subDomainMaxLenght = 253
    name
      .split('.')
      .map(label ⇒ trim(normalize(label)))
      .mkString(".")
      .take(subDomainMaxLenght)
      .stripSuffix(".")
  }
}

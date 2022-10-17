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

import java.text.Normalizer

/**
 * A collection of methods to apply names for common Kubernetes resources.
 */
object Name {
  private def maxStringLength(maxLength: Int)(s: String): String = {
    require(s.length <= maxLength, s"maximum size for this parameter is $maxLength characters")
    s
  }
  private val max15Chars = maxStringLength(15) _

  /**
   * Limit the length of a name to 63 characters.
   * Some subsystem of Kubernetes cannot manage longer names: https://kubernetes.io/docs/concepts/overview/working-with-objects/names/#dns-label-names
   */
  private def truncateTo63Characters(name: String): String = name.take(63)

  /**
   * Limit the length of a name to 253 characters.
   * Some subsystem of Kubernetes cannot manage longer names: https://kubernetes.io/docs/concepts/overview/working-with-objects/names/#dns-subdomain-names
   */
  private def truncateTo253Characters(name: String): String = name.take(253)

  /**
   * Limit the length of a name to 63 characters, including the added suffix.
   * Some subsystem of Kubernetes cannot manage longer names.
   */
  private def truncateTo63CharactersWithSuffix(name: String, suffix: String): String =
    name.take(63 - suffix.length) + suffix

  /**
   * Removes from the leading and trailing positions the specified characters.
   */
  private def trim(name: String, characters: List[Char]): String =
    name.dropWhile(characters.contains(_)).reverse.dropWhile(characters.contains(_)).reverse

  /**
   * Make a name compatible with DNS 1039 standard: like a single domain name segment.
   * Regex to follow: [a-z]([-a-z0-9]*[a-z0-9])
   * Limit the resulting name to 63 characters
   */
  private[operator] def makeDNS1039Compatible(name: String): String = {
    val normalized =
      Normalizer.normalize(name, Normalizer.Form.NFKD).toLowerCase.replaceAll("[_.]", "-").replaceAll("[^-a-z0-9]", "")
    trim(truncateTo63Characters(normalized), List('-'))
  }

  /**
   * Makes a name compatible with DNS 1123 standard: like a full domain name, max 63 characters
   * Regex to follow: [a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*
   * Limits the resulting name to 63 characters
   * https://kubernetes.io/docs/concepts/overview/working-with-objects/names/#dns-label-names
   */
  private[operator] def makeDNS1123CompatibleLabelName(name: String): String = {
    val normalized =
      Normalizer.normalize(name, Normalizer.Form.NFKD).toLowerCase.replace('_', '-').replaceAll("[^-a-z0-9.]", "")
    trim(truncateTo63Characters(normalized), List('-', '.'))
  }

  /**
   * Makes a name compatible with DNS 1123 standard: like a full domain name, max 253 characters
   * Regex to follow: [a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*
   * Limits the resulting name to 253 characters
   * https://kubernetes.io/docs/concepts/overview/working-with-objects/names/#dns-subdomain-names
   */
  private[operator] def makeDNS1123CompatibleSubDomainName(name: String): String = {
    val normalized =
      Normalizer.normalize(name, Normalizer.Form.NFKD).toLowerCase.replace('_', '-').replaceAll("[^-a-z0-9.]", "")
    trim(truncateTo253Characters(normalized), List('-', '.'))
  }

  def ofNamespace(appId: String) =
    s"app-$appId"

  def ofCloudflowOperatorDeployment = "cloudflow-operator"

  def ofServiceAccount =
    "cloudflow-app-serviceaccount"

  def ofAkkaRoleBinding =
    "cloudflow-app-akka-rolebinding"

  def ofAkkaRole =
    "cloudflow-app-akka-role"

  def ofDockerRegistrySecret =
    "cloudflow-app-docker-registry"

  def ofRoleBinding =
    "cloudflow-app-rolebinding"

  def ofPod(streamletDeploymentName: String) =
    makeDNS1039Compatible(fixDots(streamletDeploymentName))

  def ofLabelValue(name: String) =
    truncateTo63Characters(name)

  def ofVolume(name: String) =
    truncateTo63Characters(name)

  private[operator] def fixDots(name: String) = name.replace(".", "-")

  def ofContainerPort(port: Int) = max15Chars(s"c-port-$port")

  val ofContainerAdminPort = max15Chars("admin")

  val ofContainerPrometheusExporterPort = max15Chars("prom-metrics")

  def ofService(streamletDeploymentName: String) =
    truncateTo63CharactersWithSuffix(ofPod(streamletDeploymentName), "-service")

  def ofAdminService(streamletDeploymentName: String) =
    s"${ofPod(streamletDeploymentName)}-admin-service"

  def ofServicePort(port: Int) =
    s"s-port-$port"

  val ofPVCComponent = {
    "cloudflow-app-pvc"
  }

  def ofPVCInstance(appId: String, runtime: String): String =
    truncateTo63CharactersWithSuffix(appId, s"-$runtime-pvc")
}

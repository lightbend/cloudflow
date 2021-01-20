/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.kubeclient

import akka.datap.crd.App

import scala.util.Try
import akka.cli.cloudflow.models

object KubeClient {

  val CloudflowResource = App.ResourceName

  val CloudflowProtocolVersionConfigMap = "cloudflow-protocol-version"

  val ProtocolVersionKey = "protocol-version"

  val SparkResource = "sparkapplications.sparkoperator.k8s.io"

  val FlinkResource = "flinkapplications.flink.k8s.io"

  val ImagePullSecretName = "cloudflow-image-pull-secret"

  val CloudflowAppServiceAccountName = "cloudflow-app-serviceaccount"

  val KafkaClusterNameLabel = "cloudflow.lightbend.com/kafka-cluster-name"

}

trait KubeClient {

  def createNamespace(name: String): Try[Unit]

  def createImagePullSecret(
      namespace: String,
      dockerRegistryURL: String,
      dockerUsername: String,
      dockerPassword: String): Try[Unit]

  def getAppInputSecret(name: String): Try[String]

  def getOperatorProtocolVersion(): Try[String]

  // C
  def createCloudflowApp(spec: App.Spec): Try[String]

  def uidCloudflowApp(name: String): Try[String]

  def configureCloudflowApp(
      name: String,
      uid: String,
      appConfig: String,
      loggingContent: Option[String],
      createSecrets: Boolean,
      configs: Map[App.Deployment, Map[String, String]]): Try[Unit]

  // R
  def readCloudflowApp(name: String): Try[Option[App.Cr]]

  // U
  def updateCloudflowApp(app: App.Cr): Try[App.Cr]

  // D
  def deleteCloudflowApp(app: String): Try[Unit]

  def listCloudflowApps(): Try[List[models.CRSummary]]

  def getCloudflowAppStatus(app: String): Try[models.ApplicationStatus]

  def getPvcs(namespace: String): Try[List[String]]

  def getKafkaClusters(namespace: Option[String]): Try[Map[String, String]]

  def sparkAppVersion(): Try[String]

  def flinkAppVersion(): Try[String]
}

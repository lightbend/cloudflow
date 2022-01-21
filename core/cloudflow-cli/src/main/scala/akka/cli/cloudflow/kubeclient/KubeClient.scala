/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.kubeclient

import akka.datap.crd.App

import scala.util.Try
import akka.cli.cloudflow.models

object KubeClient {

  val CloudflowResource = App.ResourceName

  val SparkResource = "sparkapplications.sparkoperator.k8s.io"

  val FlinkResource = "flinkapplications.flink.k8s.io"

  val ImagePullSecretName = "cloudflow-image-pull-secret"

  val LoggingSecretName = "logging"

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

  def getAppInputSecret(name: String, namespace: String): Try[String]

  def getOperatorProtocolVersion(namespace: Option[String]): Try[String]

  // C
  def createCloudflowApp(spec: App.Spec, namespace: String): Try[String]

  def uidCloudflowApp(name: String, namespace: String): Try[String]

  def configureCloudflowApp(
      name: String,
      namespace: String,
      uid: String,
      appConfig: String,
      loggingContent: Option[String],
      configs: Map[App.Deployment, Map[String, String]]): Try[Unit]

  // R
  def readCloudflowApp(name: String, namespace: String): Try[Option[App.Cr]]

  // U
  def updateCloudflowApp(app: App.Cr, namespace: String): Try[App.Cr]

  // D
  def deleteCloudflowApp(app: String, namespace: String): Try[Unit]

  def listCloudflowApps(namespace: Option[String]): Try[List[models.CRSummary]]

  def getCloudflowAppStatus(app: String, namespace: String): Try[models.ApplicationStatus]

  def getPvcs(namespace: String): Try[List[String]]

  def getKafkaClusters(namespace: Option[String]): Try[Map[String, String]]
}

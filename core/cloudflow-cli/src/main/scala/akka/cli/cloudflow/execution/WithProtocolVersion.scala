/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import akka.datap.crd.App

import akka.cli.cloudflow.{ CliException, CliLogger }
import akka.cli.cloudflow.kubeclient.KubeClient

import scala.util.{ Failure, Success, Try }

trait WithProtocolVersion {

  def validateProtocolVersion(client: KubeClient, namespace: Option[String], logger: CliLogger): Try[String] = {
    (for {
      version <- client.getOperatorProtocolVersion(namespace)
    } yield {
      logger.info(s"Protocol version found: $version")
      version match {
        case v if v == App.ProtocolVersion =>
          Success(version)
        case ver =>
          val pVersion = Integer.parseInt(App.ProtocolVersion)
          Integer.parseInt(ver) match {
            case v if pVersion > v =>
              Failure(CliException(
                "This version of kubectl cloudflow is not compatible with the Cloudflow operator, please upgrade the Cloudflow operator"))
            case v if pVersion < v =>
              Failure(CliException(
                "This version of kubectl cloudflow is not compatible with the Cloudflow operator, please upgrade kubectl cloudflow"))
          }
      }
    }).flatten
  }

}

/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import akka.cli.cloudflow.CliException
import akka.datap.crd.App

import scala.util.{ Failure, Success, Try }

trait WithUpdateReplicas {

  def getStreamletsReplicas(appCr: Option[App.Cr]): Map[String, Int] = {
    appCr
      .map { app =>
        (for {
          streamlet <- app.getSpec().deployments
        } yield {
          streamlet.replicas match {
            case Some(r) => Some(streamlet.streamletName -> r)
            case _       => None
          }
        }).flatten.toMap
      }
      .getOrElse(Map())
  }

  def updateReplicas(crApp: App.Cr, replicas: Map[String, Int]): Try[App.Cr] = {
    val allStreamlets = crApp.getSpec().deployments.map { streamlet => streamlet.streamletName }.distinct

    (replicas.keys.toList.distinct.diff(allStreamlets)) match {
      case Nil =>
        val clusterDeployments = crApp.getSpec().deployments.map { streamlet =>
          streamlet.streamletName match {
            case sname if replicas.contains(sname) =>
              streamlet.copy(replicas = Some(replicas(sname)))
            case _ => streamlet
          }
        }
        crApp.setSpec(crApp.getSpec().copy(deployments = clusterDeployments))
        Success(crApp)
      case missings =>
        Failure(
          CliException(s"Streamlets to scale: [${missings.mkString(", ")}] are not present in the application spec"))
    }
  }

}

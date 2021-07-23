/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import akka.cli.cloudflow.CliException
import akka.datap.crd.App

import scala.util.{ Failure, Success, Try }

// This can be deprecated when VolumeMount API is deprecated.
trait WithUpdateVolumeMounts {
  def updateVolumeMounts(
      crApp: App.Cr,
      volumeMountsArgs: Map[String, String],
      pvcs: () => Try[List[String]]): Try[App.Cr] = {
    for {
      streamletVolumeNameToPvc <- streamletVolumeNameToPvcMap(crApp, volumeMountsArgs, pvcs)
      _ <- missingStreamletVolumeMountNames(crApp, streamletVolumeNameToPvc.keys)
    } yield crApp.copy(spec = crApp.spec.copy(
      deployments = updatedDeployments(crApp, streamletVolumeNameToPvc),
      streamlets = updatedStreamlets(crApp, streamletVolumeNameToPvc)))
  }

  private def streamletVolumeNameToPvcMap(
      crApp: App.Cr,
      volumeMountsArgs: Map[String, String],
      pvcs: () => Try[List[String]]): Try[Map[(String, String), String]] = {
    for {
      existingPvcs <- pvcs()
      map <- Try {
        volumeMountsArgs.map {
          case (streamletVolumeNamePath, pvcName) =>
            if (!existingPvcs.contains(pvcName)) {
              throw new CliException(
                s"Cannot find persistent volume claim '$pvcName' specified via --volume-mount argument.")
            }
            // volumeMounts Map is "<streamlet-name>.<volume-mount-name>" -> pvc-name
            val parts = streamletVolumeNamePath.split("\\.").toList
            if (parts.size != 2) {
              throw new CliException(
                "--volume-mount argument is invalid, please provide as --volume-mount <streamlet-name>.<volume-mount-name>=<pvc-name>")
            }
            val streamletName = parts(0)
            val volumeMountName = parts(1)
            if (crApp.spec.deployments.find(_.streamletName == streamletName).isEmpty) {
              throw new CliException(s"Cannot find streamlet '$streamletName' in --volume-mount argument")
            }

            if (crApp.spec.deployments
                  .filter(_.streamletName == streamletName)
                  .flatMap(_.volumeMounts)
                  .find(_.name == volumeMountName)
                  .isEmpty) {
              throw new CliException(
                s"Cannot find volume mount name '$volumeMountName' for streamlet '$streamletName' in --volume-mount argument")
            }
            (streamletName, volumeMountName) -> pvcName
        }.toMap
      }
    } yield map
  }

  private def missingStreamletVolumeMountNames(
      crApp: App.Cr,
      streamletVolumeNamesFromArgs: Iterable[(String, String)]): Try[Unit] = {
    Try {
      val missing = (streamletVolumeMountNamesInCr(crApp) -- streamletVolumeNamesFromArgs.toSet)
        .map { case (streamletName, volumeName) => s"$streamletName.$volumeName" }
        .toSeq
        .sorted
      if (missing.nonEmpty) {
        def plural = s"""${if (missing.size > 1) "s" else ""}"""
        throw new CliException(
          s"""Please provide persistent volume name$plural with --volume-mount argument$plural (replace 'pvc-name'$plural with correct value$plural):\n
          |${missing.map(m => s"--volume-mount $m=pvc-name").mkString("\n")}
          """.stripMargin)
      }
      ()
    }
  }

  private def streamletVolumeMountNamesInCr(crApp: App.Cr): Set[(String, String)] = {
    crApp.spec.deployments
      .flatMap(deployment => deployment.volumeMounts.map(vm => deployment.streamletName -> vm.name))
      .toSet
  }

  private def updatedDeployments(
      crApp: App.Cr,
      streamletVolumeNameToPvc: Map[(String, String), String]): Seq[App.Deployment] = {
    crApp.spec.deployments.map { deployment =>
      deployment.copy(volumeMounts = deployment.volumeMounts.map { vmd =>
        streamletVolumeNameToPvc
          .get((deployment.streamletName, vmd.name))
          .map(pvcName => vmd.copy(pvcName = Some(pvcName)))
          .getOrElse(vmd)
      })
    }
  }

  private def updatedStreamlets(
      crApp: App.Cr,
      streamletVolumeNameToPvc: Map[(String, String), String]): Seq[App.Streamlet] = {
    crApp.spec.streamlets.map { streamlet =>
      streamlet.copy(descriptor = streamlet.descriptor.copy(volumeMounts = streamlet.descriptor.volumeMounts.map {
        vmd =>
          streamletVolumeNameToPvc
            .get((streamlet.name, vmd.name))
            .map(pvcName => vmd.copy(pvcName = Some(pvcName)))
            .getOrElse(vmd)
      }))
    }
  }
}

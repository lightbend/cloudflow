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
      volumeMounts: Map[String, String],
      pvcs: () => Try[List[String]]): Try[App.Cr] = {
    for {
      existingPvcs <- pvcs()
      cr <- Try {
        val streamletVolumeNameToPvc =
          volumeMounts.map {
            case (streamletVolumeNamePath, pvcName) =>
              if (!existingPvcs.contains(pvcName)) {
                throw new CliException(
                  s"Cannot find persistent volume claim $pvcName specified via --volume-mount argument.")
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
                  s"Cannot find volume mount name $volumeMountName for streamlet '$streamletName' in --volume-mount argument")
              }
              (streamletName, volumeMountName) -> pvcName
          }.toMap
        val updatedDeployments = crApp.spec.deployments.map { deployment =>
          deployment.copy(volumeMounts = deployment.volumeMounts.map { vmd =>
            streamletVolumeNameToPvc
              .get((deployment.streamletName, vmd.name))
              .map(pvcName => vmd.copy(pvcName = Some(pvcName)))
              .getOrElse(vmd)
          })
        }
        // TODO also updating descriptor, since for legacy reasons the operator uses the volume mount descriptor there..
        val updatedStreamlets = crApp.spec.streamlets.map { streamlet =>
          streamlet.copy(descriptor = streamlet.descriptor.copy(volumeMounts = streamlet.descriptor.volumeMounts.map {
            vmd =>
              streamletVolumeNameToPvc
                .get((streamlet.name, vmd.name))
                .map(pvcName => vmd.copy(pvcName = Some(pvcName)))
                .getOrElse(vmd)
          }))
        }

        crApp.copy(spec = crApp.spec.copy(deployments = updatedDeployments, streamlets = updatedStreamlets))
      }
    } yield cr
  }
}

/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.operator.action

import akka.datap.crd.App
import cloudflow.operator.action.Common.jsonToConfig
import io.fabric8.kubernetes.api.model.{ OwnerReference, OwnerReferenceBuilder }

// TODO: separate the concerns and model better this class
object Util {

  val PrometheusAgentKey = "prometheus"

  def getOwnerReference(name: String, uid: String): OwnerReference = {
    new OwnerReferenceBuilder()
      .withController(true)
      .withBlockOwnerDeletion(true)
      .withApiVersion(App.ApiVersion)
      .withKind(App.Kind)
      .withName(name)
      .withUid(uid)
      .build()
  }

  // TODO: we should keep a single source of truth for model
  import cloudflow.blueprint.VolumeMountDescriptor
  import cloudflow.blueprint.deployment._
  def toBlueprint(deployment: App.Deployment): StreamletDeployment = {
    StreamletDeployment(
      name = deployment.name,
      runtime = deployment.runtime,
      image = deployment.image,
      streamletName = deployment.streamletName,
      className = deployment.className,
      endpoint = deployment.endpoint.flatMap { endpoint =>
        for {
          appId <- endpoint.appId
          streamlet <- endpoint.streamlet
          containerPort <- endpoint.containerPort
        } yield {
          Endpoint(appId = appId, streamlet = streamlet, containerPort = containerPort)
        }
      },
      secretName = deployment.secretName,
      config = jsonToConfig(deployment.config),
      portMappings = deployment.portMappings.map {
        case (k, v) =>
          k -> Topic(id = v.id, cluster = v.cluster, config = jsonToConfig(v.config))
      },
      volumeMounts = {
        if (deployment.volumeMounts == null || deployment.volumeMounts.isEmpty) None
        else
          Some(deployment.volumeMounts.map { vmd =>
            VolumeMountDescriptor(
              name = vmd.appId,
              path = vmd.path,
              accessMode = vmd.accessMode,
              pvcName = vmd.pvcName.getOrElse(""))
          }.toList)
      },
      replicas = deployment.replicas)
  }

}

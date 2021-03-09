package cloudflow.operator.action

import akka.datap.crd.App
import io.fabric8.kubernetes.api.model.{ OwnerReference, OwnerReferenceBuilder }

object Util {

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

}

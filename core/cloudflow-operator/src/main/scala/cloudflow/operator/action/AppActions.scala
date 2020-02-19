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

package cloudflow.operator
package action

import scala.collection.immutable._
import scala.concurrent._

import play.api.libs.json._
import skuber.api.client._
import skuber.json.format._
import skuber.json.rbac.format._
import skuber.rbac._
import skuber._
import skuber.PersistentVolume.AccessMode
import skuber.PersistentVolumeClaim.VolumeMode

/**
 * Creates a sequence of resource actions for preparing the namespace where the application is
 * installed
 */
object AppActions {
  def apply(appId: String, namespace: String, labels: CloudflowLabels, ownerReferences: CloudflowOwnerReferences)(implicit ctx: DeploymentContext): Seq[Action[ObjectResource]] = {
    val roleAkka  = akkaRole(namespace, labels, ownerReferences)
    val roleSpark = sparkRole(namespace, labels, ownerReferences)
    val roleFlink = flinkRole(namespace, labels, ownerReferences)
    Vector(
      Action.create(roleBinding(namespace, labels, ownerReferences), roleBindingEditor),
      Action.create(roleAkka, roleEditor),
      Action.create(roleSpark, roleEditor),
      Action.create(roleFlink, roleEditor),
      Action.create(akkaRoleBinding(namespace, roleAkka, labels, ownerReferences), roleBindingEditor),
      Action.create(sparkRoleBinding(namespace, roleSpark, labels, ownerReferences), roleBindingEditor),
      Action.create(flinkRoleBinding(namespace, roleFlink, labels, ownerReferences), roleBindingEditor),
      CreatePersistentVolumeClaimAction(persistentVolumeClaim(appId, namespace, labels, ownerReferences))
    )
  }

  private def roleBinding(namespace: String, labels: CloudflowLabels, ownerReferences: CloudflowOwnerReferences): RoleBinding =
    RoleBinding(
      metadata = ObjectMeta(
        name = Name.ofRoleBinding(),
        namespace = namespace,
        labels = labels(Name.ofRoleBinding()),
        ownerReferences = ownerReferences.list
      ),
      kind = "RoleBinding",
      roleRef = RoleRef("rbac.authorization.k8s.io", "Role", BasicUserRole),
      subjects = List(
        Subject(
          None,
          "ServiceAccount",
          Name.ofServiceAccount,
          Some(namespace)
        )
      )
    )

  private def akkaRole(namespace: String, labels: CloudflowLabels, ownerReferences: CloudflowOwnerReferences): Role =
    Role(
      metadata = ObjectMeta(
        name = Name.ofAkkaRole(),
        namespace = namespace,
        labels = labels(Name.ofAkkaRole),
        ownerReferences = ownerReferences.list
      ),
      kind = "Role",
      rules = List(createEventPolicyRule)
    )

  private def akkaRoleBinding(namespace: String, role: Role, labels: CloudflowLabels, ownerReferences: CloudflowOwnerReferences): RoleBinding =
    RoleBinding(
      metadata = ObjectMeta(
        name = Name.ofAkkaRoleBinding(),
        namespace = namespace,
        labels = labels(Name.ofRoleBinding),
        ownerReferences = ownerReferences.list
      ),
      kind = "RoleBinding",
      roleRef = RoleRef("rbac.authorization.k8s.io", "Role", role.metadata.name),
      subjects = List(
        Subject(
          None,
          "ServiceAccount",
          Name.ofServiceAccount,
          Some(namespace)
        )
      )
    )

  private def sparkRole(namespace: String, labels: CloudflowLabels, ownerReferences: CloudflowOwnerReferences): Role =
    Role(
      metadata = ObjectMeta(
        name = Name.ofSparkRole(),
        namespace = namespace,
        labels = labels(Name.ofSparkRole),
        ownerReferences = ownerReferences.list
      ),
      kind = "Role",
      rules = List(
        PolicyRule(
          apiGroups = List(""),
          attributeRestrictions = None,
          nonResourceURLs = List(),
          resourceNames = List(),
          resources = List("pods", "services", "configmaps", "ingresses", "endpoints"),
          verbs = List("get", "create", "delete", "list", "watch", "update")
        ),
        createEventPolicyRule
      )
    )

  private def sparkRoleBinding(namespace: String, role: Role, labels: CloudflowLabels, ownerReferences: CloudflowOwnerReferences): RoleBinding =
    RoleBinding(
      metadata = ObjectMeta(
        name = Name.ofSparkRoleBinding(),
        namespace = namespace,
        labels = labels(Name.ofRoleBinding),
        ownerReferences = ownerReferences.list
      ),
      kind = "RoleBinding",
      roleRef = RoleRef("rbac.authorization.k8s.io", "Role", role.metadata.name),
      subjects = List(
        Subject(
          None,
          "ServiceAccount",
          Name.ofServiceAccount,
          Some(namespace)
        )
      )
    )

  private def flinkRole(namespace: String, labels: CloudflowLabels, ownerReferences: CloudflowOwnerReferences): Role =
    Role(
      metadata = ObjectMeta(
        name = Name.ofFlinkRole(),
        namespace = namespace,
        labels = labels(Name.ofFlinkRole),
        ownerReferences = ownerReferences.list
      ),
      kind = "Role",
      rules = List(
        PolicyRule(
          apiGroups = List(""),
          attributeRestrictions = None,
          nonResourceURLs = List(),
          resourceNames = List(),
          resources = List("pods", "services", "configmaps", "ingresses", "endpoints"),
          verbs = List("get", "create", "delete", "list", "watch", "update")
        ),
        createEventPolicyRule
      )
    )

  private def flinkRoleBinding(namespace: String, role: Role, labels: CloudflowLabels, ownerReferences: CloudflowOwnerReferences): RoleBinding =
    RoleBinding(
      metadata = ObjectMeta(
        name = Name.ofFlinkRoleBinding(),
        namespace = namespace,
        labels = labels(Name.ofRoleBinding),
        ownerReferences = ownerReferences.list
      ),
      kind = "RoleBinding",
      roleRef = RoleRef("rbac.authorization.k8s.io", "Role", role.metadata.name),
      subjects = List(
        Subject(
          None,
          "ServiceAccount",
          Name.ofServiceAccount,
          Some(namespace)
        )
      )
    )

  private def persistentVolumeClaim(appId: String, namespace: String, labels: CloudflowLabels, ownerReferences: CloudflowOwnerReferences)(
      implicit ctx: DeploymentContext
  ): PersistentVolumeClaim = {
    val metadata = ObjectMeta(
      name = Name.ofPVCInstance(appId),
      namespace = namespace,
      labels = labels(Name.ofPVCComponent),
      ownerReferences = ownerReferences.list
    )

    val pvcSpec = PersistentVolumeClaim.Spec(
      accessModes = List(AccessMode.ReadWriteMany),
      volumeMode = Some(VolumeMode.Filesystem),
      resources = Some(
        Resource.Requirements(
          limits = Map(Resource.storage   -> ctx.persistentStorageSettings.resources.limit),
          requests = Map(Resource.storage -> ctx.persistentStorageSettings.resources.request)
        )
      ),
      storageClassName = Some(ctx.persistentStorageSettings.storageClassName),
      selector = None
    )
    PersistentVolumeClaim(metadata = metadata, spec = Some(pvcSpec), status = None)
  }

  val BasicUserRole = "system:basic-user"
  private val createEventPolicyRule = PolicyRule(
    apiGroups = List(""),
    attributeRestrictions = None,
    nonResourceURLs = List(),
    resourceNames = List(),
    resources = List("events"),
    verbs = List("get", "create", "update")
  )

  private def roleEditor: ObjectEditor[Role]               = (obj: Role, newMetadata: ObjectMeta) ⇒ obj.copy(metadata = newMetadata)
  private def roleBindingEditor: ObjectEditor[RoleBinding] = (obj: RoleBinding, newMetadata: ObjectMeta) ⇒ obj.copy(metadata = newMetadata)
  private def persistentVolumeClaimEditor: ObjectEditor[PersistentVolumeClaim] =
    (obj: PersistentVolumeClaim, newMetadata: ObjectMeta) ⇒ obj.copy(metadata = newMetadata)

  /**
   * Creates an action for creating a Persistent Volume Claim.
   */
  object CreatePersistentVolumeClaimAction {
    def apply(service: PersistentVolumeClaim)(implicit format: Format[PersistentVolumeClaim],
                                              resourceDefinition: ResourceDefinition[PersistentVolumeClaim]) =
      new CreatePersistentVolumeClaimAction(service, format, resourceDefinition)
  }

  class CreatePersistentVolumeClaimAction(
      override val resource: PersistentVolumeClaim,
      format: Format[PersistentVolumeClaim],
      resourceDefinition: ResourceDefinition[PersistentVolumeClaim]
  ) extends CreateAction[PersistentVolumeClaim](resource, format, resourceDefinition, persistentVolumeClaimEditor) {
    override def execute(client: KubernetesClient)(implicit ec: ExecutionContext,
                                                   lc: LoggingContext): Future[Action[PersistentVolumeClaim]] =
      for {
        pvcResult ← client.getOption[PersistentVolumeClaim](resource.name)(format, resourceDefinition, lc)
        res ← pvcResult
          .map(_ ⇒ Future.successful(CreatePersistentVolumeClaimAction(resource)))
          .getOrElse(client.create(resource)(format, resourceDefinition, lc).map(o ⇒ CreatePersistentVolumeClaimAction(o)))
      } yield res
  }
}

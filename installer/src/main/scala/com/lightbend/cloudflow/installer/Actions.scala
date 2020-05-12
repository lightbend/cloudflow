package cloudflow.installer

final case class Actions(availableClusterFeatures: ClusterFeatures) {
  def installCloudflow()(implicit cr: CloudflowInstance.CR) =
    CompositeAction(
      "install-cloudflow",
      availableClusterFeatures,
      List(
        Strimzi(),
        SparkOperator(),
        FlinkOperator(),
        AddSccToSparkServiceAccount(),
        CloudflowOperatorManagedStrimzi(),
        PatchOwnerReferenceOfSparkMutatingWebhookConfig()
      )
    )
  def uninstallCloudflow()(implicit cr: CloudflowInstance.CR) =
    CompositeAction(
      "uninstall-cloudflow",
      availableClusterFeatures,
      List(
        RemoveSccFromSparkServiceAccount(),
        RemoveCloudflowClusterwideResources(),
        RemoveCloudflowNamespacedResources()
      )
    )
}

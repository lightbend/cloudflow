package cloudflow.installer

case class CloudflowLabels(id: String) {

  import CloudflowLabels._

  val baseLabels: Map[String, String] = Map(
    InstanceId -> id,
    ManagedBy  -> CloudflowLabels.ManagedByCloudflow
  )
}

object CloudflowLabels {

  def apply(instance: CloudflowInstance.CR): CloudflowLabels =
    CloudflowLabels(instance.metadata.name)

  // The tool being used to manage the operation of an application
  val ManagedBy = "app.kubernetes.io/managed-by"
  // Managed by
  val ManagedByCloudflow = "cloudflow-installer"
  // Identifier of instance
  val InstanceId = "com.lightbend.cloudflow/instance-id"
}

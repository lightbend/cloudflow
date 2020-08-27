package cloudflow.installer
import skuber._

object TestInstance {

  import CloudflowInstance._

  def get(): CustomResource[Spec, Status] = {
    val kafkaClusterCr    = KafkaClusterCR("strimzi", "0.16.2", "gp2", "gp2", "strimzi/kafka", "0.16.2-kafka-2.4.0", "strimzi/operator", "0.16.2")
    val flinkOperator     = FlinkOperator("0.8.2", "flink-service-account", "lightbend/flinkk8soperator", "v0.5.0")
    val sparkOperator     = SparkOperator("0.6.7", "lightbend/sparkoperator", "2.0.9-cloudflow-spark-2.4.5-1.1.2-scala-2.12", "alpine:3.9")
    val cloudflowOperator = CloudflowOperator("lightbend/cloudflow-operator", "2.0.0", "glusterfs-storage")
    val spec = Spec(
      kafkaClusterCr,
      flinkOperator,
      sparkOperator,
      cloudflowOperator
    )
    val names = Definition.spec.names
    new CustomResource[Spec, Status](names.kind,
                                     Definition.spec.apiPathPrefix,
                                     ObjectMeta(name = "test", namespace = "test-namespace"),
                                     spec,
                                     None)
  }
}

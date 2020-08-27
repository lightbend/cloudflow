
strimzi_chart_version                := "0.16.2"
spark_operator_chart_version         := "0.6.7"
flink_operator_chart_version         := "0.8.2"

cloudflow_operator_chart_name        := cloudflow-environment
cloudflow_operator_chart_repo        := git@github.com:lightbend/cloudflow.git
cloudflow_operator_chart_repo_tag    := master
cloudflow_operator_chart_dir_in_repo := installer/cloudflow-environment

cloudflow_operator_image_name        := lightbend/cloudflow-operator
cloudflow_operator_image_tag         := 2.0.9
spark_operator_image_name            := lightbend/sparkoperator
spark_operator_image_tag             := 2.0.9-cloudflow-spark-2.4.5-1.1.2-scala-2.12
flink_operator_image_name            := lightbend/flinkk8soperator
flink_operator_image_tag             := v0.5.0
kafka_image_name                     := strimzi/kafka
kafka_image_tag                      := 0.16.2-kafka-2.4.0
strimzi_operator_image_name          := strimzi/operator
strimzi_operator_image_tag           := 0.16.2
webhook_patch_job_image              := alpine:3.12

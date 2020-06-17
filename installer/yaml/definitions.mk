
strimzi_chart_version                := "0.16.2"
spark_operator_chart_version         := "0.6.7"
flink_operator_chart_version         := "0.8.2"

spark_operator_image_name := lightbend/sparkoperator

cloudflow_operator_chart_name        := cloudflow-environment
cloudflow_operator_chart_repo        := git@github.com:lightbend/cloudflow.git
cloudflow_operator_chart_repo_tag    := master
cloudflow_operator_chart_dir_in_repo := installer/cloudflow-environment
cloudflow_operator_image_name        := lightbend/cloudflow-operator

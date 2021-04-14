  ifeq ($(version),)
    $(error variable 'version' must be manually specified when running a release task)
  endif

MACHINE_ID ?= $(shell sh -c "system_profiler SPHardwareDataType | grep 'Serial Number (system)' | sed 's/Serial Number (system): //' | sed 's/ *//g' | tr '[:upper:]' '[:lower:]'")
DOCKER_REGISTRY ?= "docker.io"
DOCKER_REPOSITORY ?= "lightbend"

.PHONY: all
all: | spawn-gke-cluster prepare-swiss-knife prepare-cluster run-it-tests delete-gke-cluster

.PHONY: on-cluster
on-cluster: | prepare-swiss-knife prepare-cluster run-it-tests

.PHONY: prepare-swiss-knife
prepare-swiss-knife:
	@echo '****** Prepare and publish the swiss-knife application'
	(cd swiss-knife && \
		CLOUDFLOW_VERSION=${version}  \
			sbt \
				'set version in ThisBuild := "${version}"' \
				'set cloudflowDockerRegistry in ThisBuild := Some("${DOCKER_REGISTRY}")' \
				'set cloudflowDockerRepository in ThisBuild := Some("${DOCKER_REPOSITORY}")' \
				clean buildApp)
	@echo '****** Copy the cr file to the itests relevant folder'
	(cp swiss-knife/target/swiss-knife.json src/it/resources/)

.PHONY: prepare-cluster
prepare-cluster:
	@echo '****** Cluster setup, kubectl needs to be configured'
	@echo '****** Creating namespace'
	kubectl create ns cloudflow | true
	helm uninstall spark-operator --namespace cloudflow | true
	kubectl delete job cloudflow-patch-spark-mutatingwebhookconfig -n cloudflow | true
	@echo '****** Installing Kafka'
	helm repo add strimzi https://strimzi.io/charts/ | true
	helm repo update
	helm upgrade -i strimzi strimzi/strimzi-kafka-operator --namespace cloudflow
	(for i in 1 2 3; do kubectl apply -f kafka-cluster.yaml && break || sleep 2; done)
	@echo '****** Installing NFS provisioner'
	helm repo add stable https://charts.helm.sh/stable --force-update | true
	helm repo update
	helm upgrade -i nfs-server-provisioner stable/nfs-server-provisioner --set storageClass.provisionerName=cloudflow-nfs --namespace cloudflow
	@echo '****** Installing Flink Operator'
	(helm upgrade -i flink-operator \
		https://github.com/lightbend/flink-operator/releases/download/v0.8.2/flink-operator-0.8.2.tgz \
		--set operatorVersion="v0.5.0" \
		--namespace cloudflow)
	@echo '****** Installing Cloudflow Operator'
	helm repo add cloudflow-helm-charts https://lightbend.github.io/cloudflow-helm-charts/ | true
	helm repo update
	(helm upgrade -i cloudflow cloudflow-helm-charts/cloudflow \
		--atomic \
		--version "${version}" \
		--set kafkaClusters.default.bootstrapServers=cloudflow-strimzi-kafka-bootstrap.cloudflow:9092 \
		--namespace cloudflow)
	@echo '****** Installing Spark Operator'
	helm repo add incubator https://charts.helm.sh/incubator | true
	helm repo update
	helm upgrade -i spark-operator incubator/sparkoperator --values="spark-values.yaml" --namespace cloudflow
	@echo '****** Patch Mutating webhooks'
	kubectl apply -f spark-mutating-webhook.yaml --namespace cloudflow
	@echo '****** The cluster is ready for integration tests!'

.PHONY: prepare-cluster-no-flink
prepare-cluster-no-flink:
	@echo '****** Cluster setup, kubectl needs to be configured'
	@echo '****** Creating namespace'
	kubectl create ns cloudflow | true
	helm uninstall spark-operator --namespace cloudflow | true
	kubectl delete job cloudflow-patch-spark-mutatingwebhookconfig -n cloudflow | true
	@echo '****** Installing Kafka'
	helm repo add strimzi https://strimzi.io/charts/ | true
	helm repo update
	helm upgrade -i strimzi strimzi/strimzi-kafka-operator --namespace cloudflow
	(for i in 1 2 3; do kubectl apply -f kafka-cluster.yaml && break || sleep 2; done)
	@echo '****** Installing NFS provisioner'
	helm repo add stable https://charts.helm.sh/stable --force-update | true
	helm repo update
	helm upgrade -i nfs-server-provisioner stable/nfs-server-provisioner --set storageClass.provisionerName=cloudflow-nfs --namespace cloudflow
	@echo '****** Installing Cloudflow Operator'
	helm repo add cloudflow-helm-charts https://lightbend.github.io/cloudflow-helm-charts/ | true
	helm repo update
	(helm upgrade -i cloudflow cloudflow-helm-charts/cloudflow \
		--atomic \
		--version "${version}" \
		--set cloudflow_operator.jvm.opts="-XX:MaxRAMPercentage=90.0 -XX:+UseContainerSupport -Dcloudflow.platform.flink-enabled=false" \
		--set kafkaClusters.default.bootstrapServers=cloudflow-strimzi-kafka-bootstrap.cloudflow:9092 \
		--namespace cloudflow)
	@echo '****** Installing Spark Operator'
	helm repo add incubator https://charts.helm.sh/incubator | true
	helm repo update
	helm upgrade -i spark-operator incubator/sparkoperator --values="spark-values.yaml" --namespace cloudflow
	@echo '****** Patch Mutating webhooks'
	kubectl apply -f spark-mutating-webhook.yaml --namespace cloudflow
	@echo '****** The cluster is ready for integration tests!'

.PHONY: spawn-gke-cluster
spawn-gke-cluster:
	@echo '****** Spawn a GKE cluster to run the IT tests on'
	(bash -c "source ../../cluster_scripts/create-cluster-gke.sh it-${MACHINE_ID}")

.PHONY: delete-gke-cluster
delete-gke-cluster:
	@echo '****** Deleting your GKE cluster'
	(gcloud container clusters delete --quiet "it-${MACHINE_ID}" | true && \
	  gcloud compute disks list --format="table[no-heading](name)" --filter="name~^gke-it-${MACHINE_ID}" | xargs -n1 gcloud compute disks delete --quiet)

.PHONY: run-it-tests
run-it-tests:
	@echo '****** Run Integration Tests'
	(cd .. && \
		sbt cloudflow-it/it:test)

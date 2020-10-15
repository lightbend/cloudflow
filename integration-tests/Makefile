  ifeq ($(version),)
    $(error variable 'version' must be manually specified when running a release task)
  endif

.PHONY: all
all: | spawn-gke-cluster prepare-swiss-knife prepare-itest-osx prepare-cluster run-it-tests delete-gke-cluster

.PHONY: on-cluster
on-cluster: | prepare-swiss-knife prepare-itest-osx prepare-cluster run-it-tests

.PHONY: prepare-swiss-knife
prepare-swiss-knife:
	@echo '****** Prepare and publish the swiss-knife application'
	(cd swiss-knife && \
		CLOUDFLOW_VERSION=${version}  \
			sbt -mem 2048 \
				'set version in ThisBuild := "${version}"' \
				'set cloudflowDockerRegistry in ThisBuild := Some("docker.io")' \
				'set cloudflowDockerRepository in ThisBuild := Some("lightbend")' \
				clean buildApp)
	@echo '****** Copy the cr file to the itests relevant folder'
	(cp swiss-knife/target/swiss-knife.json itest/resources/)

.PHONY: prepare-itest-osx
prepare-itest-osx:
	@echo '****** Download and unpack kubectl-cloudflow plugin'
	$(eval bintray_version=$(shell sh -c "wget -qO - https://api.bintray.com/packages/lightbend/cloudflow-cli/kubectl-cloudflow | jq -r '.versions[] | select( . | startswith(\"${version}\") )'"))
	(wget https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-${bintray_version}-darwin-amd64.tar.gz -O kubectl_cloudflow.tar.gz && \
		tar -zxf kubectl_cloudflow.tar.gz --directory itest && \
		xattr -d com.apple.quarantine itest/kubectl-cloudflow | true)

.PHONY: prepare-cluster
prepare-cluster:
	@echo '****** Cluster setup, kubectl needs to be configured'
	@echo '****** Creating namespace'
	kubectl create ns cloudflow | true
	@echo '****** Installing Kafka'
	helm repo add strimzi https://strimzi.io/charts/ | true
	helm repo update
	helm upgrade -i strimzi strimzi/strimzi-kafka-operator --namespace cloudflow
	(for i in 1 2 3; do kubectl apply -f kafka-cluster.yaml && break || sleep 2; done)
	@echo '****** Installing NFS provisioner'
	helm repo add stable https://kubernetes-charts.storage.googleapis.com/ | true
	helm repo update
	helm upgrade -i nfs-server-provisioner stable/nfs-server-provisioner --set storageClass.provisionerName=cloudflow-nfs --namespace cloudflow
	@echo '****** Installing Spark Operator'
	helm repo add incubator http://storage.googleapis.com/kubernetes-charts-incubator | true
	helm repo update
	yq write spark-values.yaml operatorVersion "${version}-cloudflow-spark-2.4.5-1.1.2-scala-2.12" > generated-spark-values.yaml
	helm upgrade -i spark-operator incubator/sparkoperator --values="generated-spark-values.yaml" --version "0.6.7" --namespace cloudflow
	kubectl apply -f spark-mutating-webhook.yaml --namespace cloudflow
	@echo '****** Installing Flink Operator'
	(helm upgrade -i flink-operator \
		https://github.com/lightbend/flink-operator/releases/download/v0.8.2/flink-operator-0.8.2.tgz \
		--set operatorImageName="lightbend/flinkk8soperator" \
		--set operatorVersion="v0.5.0" \
		--namespace cloudflow)
	@echo '****** Installing Cloudflow Operator'
	helm repo add cloudflow-helm-charts https://lightbend.github.io/cloudflow-helm-charts/ | true
	helm repo update
	(helm upgrade -i cloudflow cloudflow-helm-charts/cloudflow \
		--atomic \
		--version "${version}" \
		--set cloudflow_operator.persistentStorageClass=nfs \
		--set kafkaClusters.default.bootstrapServers=cloudflow-strimzi-kafka-bootstrap.cloudflow:9092 \
		--namespace cloudflow)
	@echo '****** The cluster is ready for integration tests!'

.PHONY: spawn-gke-cluster
spawn-gke-cluster:
	@echo '****** Spawn a GKE cluster to run the IT tests on'
	(cd ../cluster_scripts && \
		echo $(eval machine-id=$(shell sh -c "system_profiler SPHardwareDataType | grep 'Serial Number (system)' | sed 's/Serial Number (system): //' | sed 's/ *//g' | tr '[:upper:]' '[:lower:]'")) && \
		source ./create-cluster-gke.sh "it-${machine-id}")

.PHONY: delete-gke-cluster
delete-gke-cluster:
	@echo '****** Deleting your GKE cluster'
	(echo $(eval machine-id=$(shell sh -c "system_profiler SPHardwareDataType | grep 'Serial Number (system)' | sed 's/Serial Number (system): //' | sed 's/ *//g' | tr '[:upper:]' '[:lower:]'")) && \
		gcloud container clusters delete -y "it-${machine-id}")

.PHONY: run-it-tests
run-it-tests:
	@echo '****** Run Integration Tests'
	PATH=$$PWD/itest:$$PATH echo $$PATH
	PATH=$$PWD/itest:$$PATH kubectl cloudflow version
	(PATH=$$PWD/itest:$$PATH cd itest && ginkgo -v)

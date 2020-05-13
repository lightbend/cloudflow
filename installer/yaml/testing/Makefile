SHELL:=/bin/bash

KUST_DIR := ../kustomize
WORK_DIR := work

namespace=test01
instance_id=id01

all: apply

apply: \
  apply-nfs \
  apply-strimzi-operator \
  apply-spark-operator \
  apply-flink-operator \
  apply-cloudflow-operator-managed-strimzi

delete: \
  delete-nfs \
  delete-strimzi-operator \
  delete-spark-operator \
  delete-flink-operator \
  delete-cloudflow-operator-managed-strimzi

delete-%: namespace prepare-%
	kubectl delete --ignore-not-found -k ${WORK_DIR}/$*

prepare-%: ${WORK_DIR}/%
	cp -r ${KUST_DIR}/$*/* ${WORK_DIR}/$*
	(shopt -s nullglob; \
	for i in ${WORK_DIR}/$*/kustomization.yaml ${WORK_DIR}/$*/base.yaml ${WORK_DIR}/$*/overlays/*; \
	do \
		echo $$i; \
		sed -i \
		  -e 's/__namespace__/${namespace}/' \
			-e 's/__instanceId__/${instance_id}/' \
			-e 's/__flinkOperator.serviceAccount__/pipelines-app-serviceaccount/' \
			-e 's/__cloudflowOperator.persistentStorageClass__/nfs-cloudflow/' \
			-e 's/__kafkaClusterCr.kafkaPersistentStorageClass__/standard/' \
			-e 's/__kafkaClusterCr.zookeeperPersistentStorageClass__/standard/' \
			-e 's/__sparkOperator.image__/1.3.3-OpenJDK-2.4.5-1.1.0-cloudflow-2.12/' \
			-e 's/__cloudflowOperator.imageTag__/33-7703ce5/' \
			$$i; \
	done )

namespace:
	kubectl get namespaces ${namespace} || kubectl create namespace ${namespace}

${WORK_DIR}/%: clean
	mkdir -p $@

clean:
	rm -rf ${WORK_DIR}

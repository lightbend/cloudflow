SHELL:=/bin/bash

include definitions.mk

CONFIG_DIR := config

WORK_DIR   := work
KUST_DIR   := kustomize

strimzi_operator_work_dir                          := ${WORK_DIR}/strimzi-operator
strimzi_operator_kustomize_dir                     := ${KUST_DIR}/strimzi-operator
spark_operator_work_dir                            := ${WORK_DIR}/spark-operator
spark_operator_kustomize_dir                       := ${KUST_DIR}/spark-operator
patch_owner_reference_of_spark_mutatingwebhookconfig_kustomize_dir := ${KUST_DIR}/patch-owner-reference-of-spark-mutatingwebhookconfig
patch_owner_reference_of_spark_mutatingwebhookconfig_work_dir      := ${WORK_DIR}/patch-owner-reference-of-spark-mutatingwebhookconfig
flink_operator_work_dir                            := ${WORK_DIR}/flink-operator
flink_operator_kustomize_dir                       := ${KUST_DIR}/flink-operator
cloudflow_operator_work_dir                        := ${WORK_DIR}/cloudflow-operator
cloudflow_operator_managed_strimzi_work_dir        := ${WORK_DIR}/cloudflow-operator-managed-strimzi
cloudflow_operator_managed_strimzi_kustomize_dir   := ${KUST_DIR}/cloudflow-operator-managed-strimzi
cloudflow_operator_external_strimzi_work_dir       := ${WORK_DIR}/cloudflow-operator-external-strimzi
cloudflow_operator_external_strimzi_kustomize_dir  := ${KUST_DIR}/cloudflow-operator-external-strimzi
cloudflow_operator_external_kafka_work_dir         := ${WORK_DIR}/cloudflow-operator-external-kafka
cloudflow_operator_external_kafka_kustomize_dir    := ${KUST_DIR}/cloudflow-operator-external-kafka

# strimzi
strimziReleaseName := cloudflow
sparkReleaseName := cloudflow
flinkReleaseName := cloudflow
cloudflowReleaseName := cloudflow

namespacePH := "namespacePH"
sparkOperatorImageVersionPH := sparkOperatorImageVersionPH
serviceAccountsFlinkNamePH := serviceAccountsFlinkNamePH
serviceAccountCloudflowCommercialPH := serviceAccountCloudflowCommercialPH
kafkaBootstrapServersPH := kafkaBootstrapServersPH
zookeeperHostsPH := zookeeperHostsPH
strimziVersionPH := strimziVersionPH
kafkaRWOStorageClassPH := kafkaRWOStorageClassPH
zookeeperRWOStorageClassPH := zookeeperRWOStorageClassPH
strimziClusterOperatorNamespacePH := strimziClusterOperatorNamespacePH
strimziTopicOperatorNamespacePH := strimziTopicOperatorNamespacePH
cloudflowRWMStorageClassPH := cloudflowRWMStorageClassPH
cloudflowOperatorImageTagPH := cloudflowOperatorImageTagPH

# Command tweak for Helm 3
helm_major_version := $(shell helm version | awk -F '[".]' '/version.BuildInfo/ { print $$3 }')
ifeq ($(helm_major_version), v3)
	helm_name_option :=
else
	helm_name_option := --name
endif

all: generate-yaml

generate-yaml: \
    generate-yaml-strimzi-operator \
    generate-yaml-spark-operator \
    generate-yaml-flink-operator \
    generate-yaml-cloudflow-operator-managed-strimzi \
    generate-yaml-patch-owner-reference-of-spark-mutatingwebhookconfig

####################
# strimzi-operator #
####################

    # helm-template-strimzi-operator \

generate-yaml-strimzi-operator: \
	${strimzi_operator_kustomize_dir}/base.yaml \
    ${strimzi_operator_kustomize_dir}/kustomization.yaml \
    ${strimzi_operator_kustomize_dir}/overlays \
    generate-yaml-strimzi_operator-check

helm-fetch-strimzi-operator: helm-repo-strimzi-add ${strimzi_operator_work_dir}
	(cd ${strimzi_operator_work_dir} && \
		helm pull \
			--untar \
			--version "${strimzi_chart_version}" \
			strimzi/strimzi-kafka-operator)

#helm-template-strimzi-operator: helm-fetch-strimzi-operator ${strimzi_operator_kustomize_dir}
${strimzi_operator_work_dir}/base.yaml: helm-fetch-strimzi-operator ${strimzi_operator_work_dir}
	echo "helm major version: ${helm_major_version}"
	(cd ${strimzi_operator_work_dir} && set -o pipefail && \
		helm template ${helm_name_option} "${strimziReleaseName}"  \
			--namespace "${namespacePH}" \
			strimzi-kafka-operator | cat > "../../${strimzi_operator_work_dir}/base.yaml")

##################
# spark-operator #
##################

generate-yaml-spark-operator: \
    ${spark_operator_kustomize_dir}/base.yaml \
    ${spark_operator_kustomize_dir}/kustomization.yaml \
    ${spark_operator_kustomize_dir}/overlays \
    generate-yaml-spark_operator-check

helm-fetch-spark-operator: helm-repo-incubator-add ${spark_operator_work_dir}
	(cd ${spark_operator_work_dir} && \
		helm pull \
			--untar \
			--version "${spark_operator_chart_version}" \
			incubator/sparkoperator)
# remove the jobs which are annotated for Helm as `pre-delete`, `post-delete`, `pre-upgrade` or `post-upgrade` only
	( for template in work/spark-operator/sparkoperator/templates/*.yaml ; \
		do \
			toKeep=$$(awk -F '[ ,]*' ' \
				BEGIN { deleteOnly=0 } \
				/kind: ?Job/ { inJob=1; next } \
				/kind:/ {inJob=0; next} \
				/"helm.sh\/hook"/ { \
				  if (inJob) { \
						deleteOnly=1 ; \
						for (i=3; i<=NF; i++) { \
							if ($$i != "pre-delete" && $$i != "post-delete" && $$i != "pre-upgrade" && $$i != "post-upgrade") { \
								deleteOnly=0 \
							} \
						} \
					} \
				} \
				END { \
					if (deleteOnly) { \
						print "DELETE ONLY" \
					} else { \
						print "KEEP" \
					} \
				}' $$template ) ; \
			if [ "$$toKeep" != "KEEP" ] ; \
			then \
				echo "Removing $$template" ; \
				rm $$template ; \
			fi ; \
		done )

${spark_operator_work_dir}/base.yaml: helm-fetch-spark-operator ${spark_operator_work_dir}
	(cd ${spark_operator_work_dir} && set -o pipefail && \
		helm template ${helm_name_option} "${sparkReleaseName}" \
			--namespace "${namespacePH}" \
			--values="../../${CONFIG_DIR}/spark-operator-values.yaml" \
			--set sparkJobNamespace="" \
			--set operatorImageName="${spark_operator_image_name}" \
			--set operatorVersion="${sparkOperatorImageVersionPH}" \
			sparkoperator | cat > "../../${spark_operator_work_dir}/base.yaml")

########################################################
# patch-owner-reference-of-spark-mutatingwebhookconfig #
########################################################

generate-yaml-patch-owner-reference-of-spark-mutatingwebhookconfig: \
    ${patch_owner_reference_of_spark_mutatingwebhookconfig_kustomize_dir}/base.yaml \
    ${patch_owner_reference_of_spark_mutatingwebhookconfig_kustomize_dir}/kustomization.yaml \
    generate-yaml-patch_owner_reference_of_spark_mutatingwebhookconfig-check

${patch_owner_reference_of_spark_mutatingwebhookconfig_work_dir}/base.yaml: ${patch_owner_reference_of_spark_mutatingwebhookconfig_work_dir}
	cp resources/patch-owner-reference-of-spark-mutatingwebhookconfig/patch_mutatingwebhookconfig.yaml ${patch_owner_reference_of_spark_mutatingwebhookconfig_work_dir}/base.yaml

##################
# flink-operator #
##################

generate-yaml-flink-operator: \
    ${flink_operator_kustomize_dir}/base.yaml \
    ${flink_operator_kustomize_dir}/kustomization.yaml \
    ${flink_operator_kustomize_dir}/overlays \
    generate-yaml-flink_operator-check

helm-fetch-flink-operator: ${flink_operator_work_dir}
	(cd ${flink_operator_work_dir} && \
		helm pull --untar https://github.com/lightbend/flink-operator/releases/download/v${flink_operator_chart_version}/flink-operator-${flink_operator_chart_version}.tgz)

${flink_operator_work_dir}/base.yaml: helm-fetch-flink-operator ${flink_operator_work_dir}
	(cd ${flink_operator_work_dir} && set -o pipefail && \
		helm template ${helm_name_option} "${flinkReleaseName}" \
			--namespace "${namespacePH}" \
			--set serviceAccounts.flink.create=false \
			--set serviceAccounts.flink.name=${serviceAccountsFlinkNamePH} \
			flink-operator | cat > "../../${flink_operator_work_dir}/base.yaml")

######################################
# cloudflow-operator-managed-strimzi #
######################################

generate-yaml-cloudflow-operator-managed-strimzi: \
    ${cloudflow_operator_managed_strimzi_kustomize_dir}/base.yaml \
    ${cloudflow_operator_managed_strimzi_kustomize_dir}/kustomization.yaml \
    ${cloudflow_operator_managed_strimzi_kustomize_dir}/overlays \
    generate-yaml-cloudflow_operator_managed_strimzi-check

helm-fetch-cloudflow-operator-managed-strimzi: helm-fetch-cloudflow-operator ${cloudflow_operator_managed_strimzi_work_dir}
	cp -r ${cloudflow_operator_work_dir}/${cloudflow_operator_chart_name} ${cloudflow_operator_managed_strimzi_work_dir}/${cloudflow_operator_chart_name}

${cloudflow_operator_managed_strimzi_work_dir}/base.yaml: helm-fetch-cloudflow-operator-managed-strimzi ${cloudflow_operator_managed_strimzi_work_dir}
	(cd ${cloudflow_operator_managed_strimzi_work_dir} && set -o pipefail && \
		helm template ${helm_name_option} "${cloudflowReleaseName}" \
			--namespace "${namespacePH}" \
			--values="../../${CONFIG_DIR}/gke-values.yaml" \
			--set kafka.mode="CloudflowManaged" \
			--set kafka.bootstrapServers="${kafkaBootstrapServersPH}" \
			--set kafka.zookeeperHosts="${zookeeperHostsPH}" \
			--set kafka.strimzi.version="${strimziVersionPH}" \
			--set kafka.strimzi.name="${strimziReleaseName}" \
			--set kafka.strimzi.kafka.persistentStorageClass="${kafkaRWOStorageClassPH}" \
			--set kafka.strimzi.zookeeper.persistentStorageClass="${zookeeperRWOStorageClassPH}" \
			--set kafka.strimzi.clusterOperatorNamespace="${strimziClusterOperatorNamespacePH}" \
			--set kafka.strimzi.topicOperatorNamespace="${strimziTopicOperatorNamespacePH}" \
			--set operator.image.name="${cloudflow_operator_image_name}:${cloudflowOperatorImageTagPH}"\
			--set operator.image.tag="${cloudflowOperatorImageTagPH}" \
			--set operator.resources.requests.memory="512M" \
			--set operator.resources.requests.cpu="100m" \
			--set operator.resources.limits.memory="1024M" \
			--set operator.resources.limits.cpu="1" \
			--set operator.persistentStorageClass="${cloudflowRWMStorageClassPH}",\
			${cloudflow_operator_chart_name} | cat > "../../${cloudflow_operator_managed_strimzi_work_dir}/base.yaml")

##############################
# generic cloudflow-operator #
##############################

helm-fetch-cloudflow-operator: helm-repo-lightbend-add ${cloudflow_operator_work_dir}
	cp -r ../../${cloudflow_operator_chart_dir_in_repo} ${cloudflow_operator_work_dir}/${cloudflow_operator_chart_name}

##############
# helm repos #
##############

helm-repo-strimzi-add:
	helm repo add strimzi http://strimzi.io/charts/ > /dev/null 2>&1 | true
	helm repo update

helm-repo-incubator-add:
	helm repo add incubator http://storage.googleapis.com/kubernetes-charts-incubator  > /dev/null 2>&1 | true
	helm repo update

helm-repo-lightbend-add:
	helm repo add lightbend-helm-charts https://repo.lightbend.com/helm-charts/  > /dev/null 2>&1 | true
	helm repo update

####################################
# generic kustomize resources copy #
####################################

${KUST_DIR}/%/base.yaml: ${WORK_DIR}/%/base.yaml
	mkdir -p ${KUST_DIR}/$*
	awk ' \
	/^kind:/ {ownerReferences=1} \
	/^kind: CustomResourceDefinition$$/ || /^kind: ClusterRole$$/ || /^kind: ClusterRoleBinding$$/ {ownerReferences=0} \
	ownerReferences && /^metadata:/ { \
		print; \
		print "  ownerReferences:"; \
		print "  - apiVersion: cloudflow-installer.lightbend.com/v1alpha1"; \
		print "    blockOwnerDeletion: true"; \
		print "    controller: true"; \
		print "    kind: Cloudflow"; \
		print "    name: __instanceId__"; \
		print "    uid: __instanceUid__"; \
		next \
	} \
	{print} \
	' $< > $@

${KUST_DIR}/%/kustomization.yaml: resources/kustomization.yaml
	cp $^ $@

${KUST_DIR}/%/overlays: resources/%/*.yaml
	mkdir -p $@
	cp $^ $@
	echo "" >> ${KUST_DIR}/$*/kustomization.yaml
	echo "$(^F)" | awk 'BEGIN{ RS=" "; FS="|" }{ print "- overlays/"$$0 }' >> ${KUST_DIR}/$*/kustomization.yaml

##################
# generic checks #
##################

generate-yaml-%-check:
	(set -o pipefail; kubectl kustomize $($*_kustomize_dir) | cat > ${$*_work_dir}/test.yaml) || (echo "**** Error applying Kustomize"; false)
	if (grep PH ${$*_work_dir}/test.yaml); then echo "****** Error: PH reference(s) found after applying Kustomize"; false; fi

#############################
# generic folders managment #
#############################

${WORK_DIR}/%: clean
	mkdir -p $@

${KUST_DIR}/%: clean
	mkdir -p $@

clean:
	rm -rf ${WORK_DIR} ${KUST_DIR}

#!/usr/bin/env bash

# Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Usage: install-cloudflow.sh [CLOUDFLOW_NAMESPACE] [CLUSTER_TYPE]
if [ $# -ne 2 ]; then
  echo "Not enough arguments supplied"
  echo "Usage: install-cloudflow.sh [CLOUDFLOW_NAMESPACE] [CLUSTER_TYPE]"
  exit 1
fi

# The Namespace to install all our charts in
NAMESPACE=$1
CLUSTER_TYPE=$2

currentDirectory=$(dirname "$0")

# shellcheck source=common/utils.sh
. "$currentDirectory"/utils.sh

# shellcheck source=common/shared.sh
. "$currentDirectory"/shared.sh $NAMESPACE

case $CLUSTER_TYPE in
 gke)
    # Adjust the user rights
    echo "Creating admin cluster role binding for modifying Spark role"
    kubectl create clusterrolebinding cluster-admin-binding --clusterrole cluster-admin --user "$(gcloud config get-value account)" >/dev/null

    echo "Installing NFS Server"
    result=$(install_nfs_server "$NAMESPACE" "false")
    if [ $? -ne 0 ]; then
      print_error_message "$result"
      print_error_message "installation failed"
      exit 1
    fi
    ;;
 eks)
    # Adjust the user rights
    echo "Creating admin cluster role binding for modifying Spark role"
    kubectl create clusterrolebinding cluster-admin-binding --clusterrole cluster-admin --user "$(aws iam get-user | jq -r '.User.UserName')" >/dev/null

    echo "Installing EFS Provisioner"
    file_system_id="$(aws efs describe-file-systems --query "FileSystems[?Name=='$NAMESPACE'].FileSystemId" --output json | jq -r '.[]')"
    result=$(install_efs_provisioner "$NAMESPACE" "$file_system_id" "$AWS_DEFAULT_REGION")
    if [ $? -ne 0 ]; then
      print_error_message "$result"
      print_error_message "installation failed"
      exit 1
    fi
    ;;
 *)
    print_error_message "Unknown cluster type: $CLUSTER_TYPE"
    exit 1
    ;;
esac

# shellcheck source=common/query-storageclass.sh
. "$currentDirectory"/query-storageclass.sh

# Install pre-requisite operators and CRDs
# shellcheck source=common/install-operators.sh
. "$currentDirectory"/install-operators.sh

# Call Helm with all args and overrrides, 
## TODO -- check docker note here:  is this a problem for Cloudflow OSS install?
# Note! We point the docker registry to localhost on gke, our GKE clusters cannot resolve the external address
## TODO -- check the domain here below -- not supported in OSS. Consequences?
echo "Installing Cloudflow"
result=$(helm upgrade cloudflow cloudflow-environment \
--install \
--namespace "$NAMESPACE" \
--timeout $HELM_TIMEOUT \
--values="$currentDirectory"/k8s-values.yaml \
--set \
kafka.mode="$KAFKA",\
kafka.bootstrapServers="$kafkaBootstrapServers",\
kafka.zookeeperHosts="$zookeeperHosts",\
kafka.strimzi.version="$strimziVersion",\
kafka.strimzi.name="$strimziReleaseName",\
kafka.strimzi.kafka.persistentStorageClass="$selectedRWOStorageClass",\
kafka.strimzi.zookeeper.persistentStorageClass="$selectedRWOStorageClass",\
kafka.strimzi.clusterOperatorNamespace="$strimziClusterOperatorNamespace",\
kafka.strimzi.topicOperatorNamespace="$strimziTopicOperatorNamespace",\
operator.image.name="$operatorImage",\
operator.image.tag="$operatorImageTag",\
operator.resources.requests.memory="$requestsMemory",\
operator.resources.requests.cpu="$requestsCpu",\
operator.resources.limits.memory="$limitsMemory",\
operator.persistentStorageClass="$selectedRWMStorageClass",\
operator.resources.limits.cpu="$limitsCpu")
if [ $? -ne 0 ]; then
  print_error_message "Installation failed"
  print_error_message "$result"
  exit 1
fi
echo ""
echo "+------------------------------------------------------------------------------------+"
echo "|                      Installation of Cloudflow has completed                       |"
echo "+------------------------------------------------------------------------------------+"
echo ""
kubectl get pods -n "$NAMESPACE"
exit 0

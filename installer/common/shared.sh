#!/usr/bin/env bash

# Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
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

#Usage shared.sh <NAMESPACE>

if [ $# -eq 0 ]
  then
    print_error_message "No namespace supplied"
    print_error_message "Usage: shared.sh [NAMESPACE]"
    exit 1
fi

namespace=$1

echo "Initializing shared definitions for namespace: $namespace"

set +x

# shellcheck source=common/detect.sh
. "$currentDirectory"/detect.sh

# Tiller check
. "$currentDirectory"/tiller.sh

# Utils
. "$currentDirectory"/utils.sh

export_tiller_namespace
if [ $? -ne 0 ]; then
    print_error_message "Cannot detect the namespace that Tiller is installed in."
    print_error_message ""
    print_error_message "This can be caused by:"
    print_error_message "1. Tiller has not yet been installed."
    print_error_message "2. The cluster is new and all components have not yet started."
    print_error_message ""
    print_error_message "Please check if helm is correctly installed using `helm version`."
    print_error_message "If the connection times out the cluster is still undergoing setup."
    print_error_message "In that case please wait a few minutes before trying again."
    print_error_message ""
    exit 1
fi
echo "The tiller namespace is '$TILLER_NAMESPACE'"


# Cloudflow operator version
export operatorImageName="lightbend/cloudflow-operator"
export operatorImageTag="33-7703ce5"
export operatorImage="$operatorImageName:$operatorImageTag"


# Kafka installation mode CloudflowManaged|ExternalStrimzi|External
export KAFKA="${KAFKA:-CloudflowManaged}"

# Flink
export flinkReleaseName="cloudflow-flink"
export flinkOperatorChartVersion="0.6.0"
export flinkOperatorNamespace=""
export installFlinkOperator=false

# Strimzi
export strimziReleaseName="cloudflow-strimzi"
export strimziVersion="0.13.0"
export installStrimzi="${installStrimzi:-false}"
export strimziClusterOperatorNamespace=""
export strimziTopicOperatorNamespace=""

# Kafka & ZooKeeper clusters
export kafkaBootstrapServers="cloudflow-strimzi-kafka-bootstrap.$namespace:9092"
export zookeeperHosts=""

# Spark Operator
export sparkOperatorReleaseName="cloudflow-sparkoperator"
export sparkOperatorChartVersion="0.4.0"
export sparkOperatorImageVersion="1.3.0-M1-OpenJDK-2.4.4-0.8.2-cloudflow-2.12"
export sparkOperatorNamespace="$namespace"


# Check whether the mutating admission webhook has been enabled
webhookResult=$(detect_mutating_webhook)
# shellcheck disable=SC2086
if [ $webhookResult -eq 0 ]; then
    print_error_message ""
    print_error_message "The mutating admission webhook is not enabled in the cluster. It is required for running Spark streamlets."
    print_error_message "Please enable it before installing Cloudflow."
    print_error_message ""
    print_error_message "More information can be found here: https://kubernetes.io/docs/reference/access-authn-authz/admission-controllers/#mutatingadmissionwebhook"
    print_error_message ""
    exit 1
else
    echo "The cluster has enabled the mutating admission webhook."
fi

# Check if flink operator already is installed
flinkOperatorNamespace=$(detect_flinkoperator)
if [ -z "$flinkOperatorNamespace" ]
then
    echo "The Flink operator is not installed, will install in '$namespace'"
    installFlinkOperator=true
    flinkOperatorNamespace=$namespace
else
    echo "Flink operator is already installed in '$flinkOperatorNamespace'"
    found=$(detect_flink_watch_all_namespaces)
    if [ "$found" == false ]
    then
        print_error_message "The Flink operator installed is not configured to watch all namespaces in the cluster."
        print_error_message "Please re-configure the Flink operator to watch all namespaces before restarting the Cloudflow installation."
        exit 1
    fi
fi

echo "Kafka installation mode is: '${KAFKA}'."
# Default case
if [ "${KAFKA}" = "CloudflowManaged" ]; then
    # Check if Strimzi already is installed
    strimziClusterOperatorNamespace=$(detect_strimzi)
    if [ -z "$strimziClusterOperatorNamespace" ]; then
      echo ""
      echo "Strimzi chart is not installed."
      echo "We will install the Strimzi chart and create a Kafka cluster into the namespace '$namespace'."
      installStrimzi=true
      strimziClusterOperatorNamespace=$namespace
      strimziTopicOperatorNamespace=$namespace
    else
      # Check if CRDs are in place, i.e. if not this is a broken install.
      detect_strimzi_crds
      if [ $? -ne 0 ]; then
        print_error_message "Strimzi CRDs cannot be found. This indicates that Strimzi is not correctly installed."
        print_error_message "Please correct the installation of Strimzi before restarting the Cloudflow installer."
        exit 1
      fi

      echo "Strimzi chart is already installed in '$strimziClusterOperatorNamespace'."
      echo "We will create a Kafka cluster into this namespace."
      strimziTopicOperatorNamespace=$strimziClusterOperatorNamespace
      # Reconfigure kafka bootstrapservers to point to the namespace where our Kafka will be installed
      kafkaBootstrapServers="cloudflow-strimzi-kafka-bootstrap.$strimziClusterOperatorNamespace:9092"
    fi
elif [ "${KAFKA}" = "ExternalStrimzi" ]; then
    if [ -n "${KAFKA_STRIMZI_TOPIC_OPERATOR_NAMESPACE}" ]; then
        strimziTopicOperatorNamespace=$KAFKA_STRIMZI_TOPIC_OPERATOR_NAMESPACE
    else
        print_error_message "Strimzi topic operator configuration is invalid."
        print_error_message "You must define environment variable 'KAFKA_STRIMZI_TOPIC_OPERATOR_NAMESPACE' and set it to a namespace that contains a running strimzi-topic-operator pod."
        print_error_message ""
        ##TODO FIX_PIPELINES_REF
        print_error_message "More information can be found here: https://developer.lightbend.com/docs/pipelines/current/index.html#_existing_strimzi_kafka"
        print_error_message ""
        exit 1
    fi
    if [ -n "${KAFKA_BOOTSTRAP_SERVERS}" ]; then
        kafkaBootstrapServers=$KAFKA_BOOTSTRAP_SERVERS
    else
        print_error_message "Kafka bootstrap servers configuration is invalid."
        print_error_message "You must define environment variable 'KAFKA_BOOTSTRAP_SERVERS' and set it to the Kafka bootstrap servers hostname and port."
        print_error_message ""
        ##TODO FIX_PIPELINES_REF
        print_error_message "More information can be found here: https://developer.lightbend.com/docs/pipelines/current/index.html#_existing_strimzi_kafka"
        print_error_message ""
        exit 1
    fi
elif [ "${KAFKA}" = "External" ]; then
    strimziTopicOperatorNamespace=$namespace
    echo "Strimzi topic operator will be installed into '${strimziTopicOperatorNamespace}'."https://developer.lightbend.com/docs/pipelines/current/index.html#_external_kafka
    if [ -n "${KAFKA_BOOTSTRAP_SERVERS}" ]; then
        kafkaBootstrapServers=$KAFKA_BOOTSTRAP_SERVERS
    else
        print_error_message "Kafka bootstrap servers configuration is invalid."
        print_error_message "You must define environment variable 'KAFKA_BOOTSTRAP_SERVERS' and set it to the Kafka bootstrap servers hostname and port."
        print_error_message ""
        ##TODO FIX_PIPELINES_REF
        print_error_message "More information can be found here: https://developer.lightbend.com/docs/pipelines/current/index.html#_external_kafka"
        print_error_message ""
        exit 1
    fi
    if [ -n "${KAFKA_ZOOKEEPER_HOSTS}" ]; then
        zookeeperHosts=$KAFKA_ZOOKEEPER_HOSTS
    else
        print_error_message "ZooKeeper hosts configuration is invalid."
        print_error_message "You must define environment variable 'KAFKA_ZOOKEEPER_HOSTS' and set it to the ZooKeeper hostname and port."
        print_error_message ""
        ##TODO FIX_PIPELINES_REF
        print_error_message "More information can be found here: https://developer.lightbend.com/docs/pipelines/current/index.html#_external_kafka"
        print_error_message ""
        exit 1
    fi
else
    print_error_message "Invalid Kafka installation mode defined: '${KAFKA}'."
    print_error_message ""
    
    exit 1
fi

# Check if spark-operator already is installed
sparkOperatorNamespace=$(detect_sparkoperator)
export sparkOperatorNamespace
export installSparkOperator=false
if [ -z "$sparkOperatorNamespace" ]
then
    echo "The spark operator is not installed, will install in '$namespace'"
    installSparkOperator=true
    sparkOperatorNamespace=$namespace
else
    echo "Spark operator is already installed in '$sparkOperatorNamespace'"
fi

# API-server requests and limits for cpu and memory
export requestsMemory="256M"
export requestsCpu="1"
export limitsMemory="1024M"
export limitsCpu="2"

# Installs an NFS server: $1: namespace, $2: boolean onOpenShift
NFS_SERVER_NAME=cloudflow-nfs
NFS_CHART_NAME=fdp-nfs
install_nfs_server() {
helm upgrade $NFS_SERVER_NAME lightbend-helm-charts/$NFS_CHART_NAME \
--install \
--namespace "$1" \
--timeout 600 \
--set createStorage=false \
--set serviceAccount.create=false \
--set serviceAccount.name=cloudflow-operator \
--set onOpenShift="$2" \
--set storageClassName=nfs-client \
--version 0.2.0
}

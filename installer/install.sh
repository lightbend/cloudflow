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

# Utilities for pretty-printing errors
. common/utils.sh

#Check env preconditions to execute the installation (should always pass on docker!)
. common/requirements.sh

. common/cloudflow-chart-version.sh

CLOUDFLOW_NAMESPACE="cloudflow"
CLUSTER_NAME=$1
CLUSTER_TYPE=$2

case $CLUSTER_TYPE in
 gke)
    # Check that we have logged into a GKE cluster
    kubectl get pods > /dev/null 2>&1
    if [ $? -ne 0 ]; then
        print_error_message "It looks like you are not logged into a Kubernetes cluster. Please 'gcloud init' before running the installer."
        exit 1
    fi
    ;;
 eks)
    # Check AWS_DEFAULT_REGION is set
    if [[ -z "${AWS_DEFAULT_REGION}" ]]; then
        print_error_message "It looks like the AWS_DEFAULT_REGION environment variable is not set. Please set the value for the AWS_DEFAULT_REGION environment variable before running the installer."
        exit 1
    fi
    ;;
 *)
    print_error_message "Unknown cluster type: $CLUSTER_TYPE"
    exit 1
    ;;
esac

# Utility function to query Kubernetes for components
. common/detect.sh
# Utility functions for interacting with Helm
. common/helm.sh

# Create namespace
kubectl create namespace "$CLOUDFLOW_NAMESPACE"

# Init Helm
init_helm

# helm repos.
helm repo add lightbend-helm-charts https://repo.lightbend.com/helm-charts/ > /dev/null 2>&1
helm repo add strimzi http://strimzi.io/charts/ > /dev/null 2>&1
helm repo add stable https://kubernetes-charts.storage.googleapis.com/ > /dev/null 2>&1
helm repo update > /dev/null 2>&1

# Install Cloudflow
echo "Installing Cloudflow $CLOUDFLOW_CHART_VERSION"
echo " - cluster: $CLUSTER_NAME"
echo " - namespace: $CLOUDFLOW_NAMESPACE"

#######################3
## Check OK until here
#######################3

common/install-cloudflow.sh "$CLUSTER_NAME" "$CLOUDFLOW_NAMESPACE" "$CLUSTER_TYPE"

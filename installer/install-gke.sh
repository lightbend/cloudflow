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

# Utilities for pretty-printing errors
. common/utils.sh

#Check env preconditions to execute the installation (should always pass on docker!)
. common/requirements.sh

if [ -z "$1" ]
  then
    print_error_message "Please provide the cluster name of the GKE cluster where Cloudflow should be installed."
    echo "NOTE: this should not be a URL but a plain domain name."
    exit 1
fi

. common/cloudflow-chart-version.sh


export CLUSTER_NAME=$1
export CLOUDFLOW_NAMESPACE="lightbend"
export TILLER_SERVICE_ACCOUNT="tiller"
export TILLER_NAMESPACE="kube-system"

# Check that we have logged into a Kubernetes cluster
kubectl get pods > /dev/null 2>&1
if [ $? -ne 0 ]; then
    print_error_message "It looks like you are not logged into a Kubernetes cluster. Please 'gcloud init' before running the installer."
    exit 1
fi

# Utility function to query Kubernetes for components
. common/detect.sh
# Utility functions to interact with Tiller
. common/tiller.sh

# Init tiller
export_tiller_namespace
if [ $? -ne 0 ]; then
    echo "Tiller not found. Installing Tiller."

    namespaceExists=$(detect_k8s_object "kubectl get ns $TILLER_NAMESPACE")
    if [ "$namespaceExists" == "0" ]; then
    	kubectl create ns $TILLER_NAMESPACE
    fi

    kubectl create serviceaccount --namespace "$TILLER_NAMESPACE" "$TILLER_SERVICE_ACCOUNT"
    kubectl create clusterrolebinding "$TILLER_NAMESPACE":tiller --clusterrole=cluster-admin --serviceaccount="$TILLER_NAMESPACE":"$TILLER_SERVICE_ACCOUNT"
    helm init --wait --service-account "$TILLER_SERVICE_ACCOUNT" --upgrade --tiller-namespace="$TILLER_NAMESPACE"
else
    echo "Tiller found, the version of Tiller will be synchronized with the local Helm version, this may downgrade Tiller."
    read -p "Do you want to continue? (y/n) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        # Upgrade Tiller so versions match
        helm init --upgrade --force-upgrade 2>&1 > /dev/null
    else
        print_error_message "Installation cancelled."
        exit 1
    fi
fi

# Wait for tiller to deploy
kubectl rollout status deployment/tiller-deploy  -n "$TILLER_NAMESPACE"
if [ $? -ne 0 ]; then
    print_error_message "Tiller failed to deploy. Please correct the problem and re-run the installer."
    exit 1
fi

# Create namespace
kubectl create namespace "$CLOUDFLOW_NAMESPACE"

# Test the installation of Tiller
echo "Testing Tiller"
test_tiller "$CLOUDFLOW_NAMESPACE"
if [ $? -ne 0 ]; then
    exit 1
fi

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

common/gke-install-cloudflow.sh "$CLUSTER_NAME" "$CLOUDFLOW_NAMESPACE"

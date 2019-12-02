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

currentDirectory=$(dirname "$0")

# Check that we have logged into a Kubernetes cluster
kubectl get pods > /dev/null 2>&1
if [ $? -ne 0 ]; then 
    print_error_message "It looks like you have not logged into a Kubernetes cluster. Please login before running the uninstall script."
    exit 1
fi


# shellcheck source=common/detect.sh
. common/detect.sh

echo "This script will remove all Cloudflow related objects from the Kubernetes cluster currently logged in to"
read -p "Do you want to continue ? (y/n) " -n 1 -r
echo ""

if [[ $REPLY =~ ^[Yy]$ ]]; then
    
    # All applications
    echo "Removing all application namespaces..."
    kubectl get cloudflowapplications.cloudflow.lightbend.com --no-headers=true |cut -d ' ' -f 1 | xargs kubectl delete ns --cascade

    # All our charts
    echo "Removing all Helm charts..."
    helm delete cloudflow --purge --no-hooks
    helm delete cloudflow-sparkoperator --purge --no-hooks
    helm delete cloudflow-strimzi --purge --no-hooks
    helm delete cloudflow-flink --purge --no-hooks

    # The namespace
    # TODO FIX_HARDCODED_NAMESPACE 
    echo "Removing the Lightbend namespace..."
    kubectl delete ns lightbend --cascade

    if [ "$1" == "icp4d" ]; then
        kubectl delete clusterrole lightbend-role &&
        kubectl delete clusterrolebinding lightbend-psp-users &&
        kubectl delete podsecuritypolicy lightbend-admin
    fi

    # All our CRDs
    echo "Removing CRDs..."
    kubectl delete crd alertmanagers.monitoring.coreos.com \
      prometheuses.monitoring.coreos.com \
      prometheusrules.monitoring.coreos.com \
      scheduledsparkapplications.sparkoperator.k8s.io \
      servicemonitors.monitoring.coreos.com \
      sparkapplications.sparkoperator.k8s.io \
      flinkapplications.flink.k8s.io \
      cloudflowapplications.cloudflow.lightbend.com \
      cloudflowapplications.lightbend.com \
      --ignore-not-found=true

    echo "Done !"
else
    echo "Script cancelled !"
fi

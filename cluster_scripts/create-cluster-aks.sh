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

function usage {
  cat<< EOF
  This script creates a Kubernetes cluster on Azure Kubernetes Service (AKS)
  Usage: $SCRIPT  [OPTIONS]
  eg: $SCRIPT --cluster-name my-cluster --resource-group my-group
  Options:
  The following options determine the cluster configuration.
  -c | --cluster-name name              The name used for the cluster
  -r | --resource-group group           The resource group to use
  --location eastus                     AKS location to launch the cluster in. "eastus" by default
                                        For available locations, refer to https://azure.microsoft.com/en-us/global-infrastructure/services/?products=kubernetes-service.
  --k8s-version 1.16.9                  Kubernetes version to use. "1.16.9" by default
                                        To view all supported versions, run " az aks get-versions --location <LOCATION>"
  --node-vm-size Standard_A4_v2         Size of node VMs in the cluster. "Standard_A4_v2" by default.
                                        For a list of supported sizes, refer to https://docs.microsoft.com/en-us/azure/virtual-machines/linux/sizes.
                                        using "provision-large.config.template".
  -h | --help                           Prints this message.
EOF
}


function parse_args {
  while [ $# -gt 0 ]
  do
    case "$1" in
      -c|--cluster-name)
        shift
        CLUSTER_NAME=$1
        [ -z "$CLUSTER_NAME" ] && error '"--cluster-name" requires an argument.\n'
      ;;
      -r|--resource-group)
        shift
        RESOURCE_GROUP_NAME=$1
        [ -z "$RESOURCE_GROUP_NAME" ] && error '"--resource-group" requires an argument.\n'
      ;;
      --location)
        shift
        LOCATION=$1
        [ -z "$LOCATION" ] && error '"--location" requires an argument.\n'
      ;;
      --k8s-version)
        shift
        K8S_VERSION=$1
        [ -z "$K8S_VERSION" ] && error '"--k8s-version" requires an argument.\n'
      ;;
      --node-vm-size)
        shift
        NODE_VM_SIZE=$1
        [ -z "$NODE_VM_SIZE" ] && error '"--node-vm-size" requires an argument.\n'
      ;;
      -h|--help)      # Call a "usage" function to display a synopsis, then exit.
        usage
        exit 0
      ;;
      --)              # End of all options.
        shift
        break
      ;;
      '')              # End of all options.
        break
      ;;
      *)
        error "Unrecognized option: $1"
      ;;
    esac
    shift
  done
}

parse_args "$@"

if [ -z "$CLUSTER_NAME" ]
  then
    echo "No cluster name specified."
    usage
    exit 1
fi

if [ -z "$RESOURCE_GROUP_NAME" ]
  then
    echo "No resource group specified."
    usage
    exit 1
fi

[ -z "$LOCATION" ] && LOCATION=eastus
[ -z "$K8S_VERSION" ] && K8S_VERSION=1.16.9
[ -z "$NODE_VM_SIZE" ] && NODE_VM_SIZE=Standard_A4_v2

# Create resource group
az group create --name "$RESOURCE_GROUP_NAME" --location "$LOCATION"

# Create cluster
az aks create --resource-group "$RESOURCE_GROUP_NAME" \
  --name "$CLUSTER_NAME" \
  --node-count 5 \
  --enable-addons monitoring \
  --enable-cluster-autoscaler \
  --generate-ssh-keys \
  --kubernetes-version "$K8S_VERSION" \
  --node-vm-size $NODE_VM_SIZE \
  --min-count 1 \
  --max-count 7

# Connect to new cluster
az aks get-credentials --resource-group "$RESOURCE_GROUP_NAME" --name "$CLUSTER_NAME"

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
  --resource-group group                The resource group to use
  --location eastus                     AKS location to launch the cluster in. "eastus" by default
                                        For available locations, refer to https://azure.microsoft.com/en-us/global-infrastructure/services/?products=kubernetes-service.
  --k8s-version 1.17.3                  Kubernetes version to use. "1.17.3" by default
                                        To view all supported versions, run " az aks get-versions --location <LOCATION>"
  --node-vm-size Standard_A8_v2         Size of node VMs in the cluster. "Standard_A8_v2" by default.
                                        For a list of supported sizes, refer to https://docs.microsoft.com/en-us/azure/virtual-machines/linux/sizes.
                                        using "provision-large.config.template".
  --node-count 5                        Number of nodes to launch. 5 by default.
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
        [ -z "$CLUSTER_NAME" ] && error '"--cluster-name" requires a non-empty option argument.\n'
      ;;
      --resource-group)
        shift
        RESOURCE_GROUP_NAME=$1
        [ -z "$RESOURCE_GROUP_NAME" ] && error '"--resource-group" requires a non-empty option argument.\n'
      ;;
      --location)
        shift
        LOCATION=$1
        [ -z "$LOCATION" ] && error LOCATION=eastus
      ;;
      --k8s-version)
        shift
        K8S_VERSION=$1
        [ -z "$K8S_VERSION" ] && K8S_VERSION=1.17.3
      ;;
      --node-vm-size)
        shift
        NODE_VM_SIZE=$1
        [ -z "$NODE_VM_SIZE" ] && NODE_VM_SIZE=Standard_A8_v2
      ;;
      --node-count)
        shift
        NODE_COUNT=$1
        [ -z "$NODE_COUNT" ] && NODE_COUNT=5
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

if [ -z "$RESOURCE_GROUP" ]
  then
    echo "No resource group specified."
    usage
    exit 1
fi

# Create resource group
az group create --name "$RESOURCE_GROUP_NAME" --location "$LOCATION"

# Create cluster
az aks create --resource-group "$RESOURCE_GROUP_NAME" \
  --name "$CLUSTER_NAME" \
  --node-count $NODE_COUNT \
  --enable-addons monitoring \
  --generate-ssh-keys \
  --kubernetes-version "$CLUSTER_VERSION" \
  --node-vm-size $NODE_VM_SIZE

# Connect to new cluster
az aks get-credentials --resource-group "$RESOURCE_GROUP_NAME" --name "$CLUSTER_NAME"

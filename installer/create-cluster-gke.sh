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

# Usage:
# gke-create-cluster.sh [CLUSTER-NAME]
if [ $# -eq 0 ]
  then
    echo "No cluster name supplied"
    echo "Usage: gke-create-cluster.sh [CLUSTER-NAME]"
    exit 1
fi

CLUSTER_NAME=$1

# Create cluster
# Versions available for new cluster masters
# https://cloud.google.com/kubernetes-engine/versioning-and-upgrades#versions_available_for_new_cluster_masters
# use command `gcloud container get-server-config` to find latest supported master GKE cluster version
gcloud container clusters create $CLUSTER_NAME \
  --cluster-version 1.13.11-gke.14  \
  --image-type cos \
  --machine-type n1-standard-4 \
  --num-nodes 3 \
  --enable-autoscaling \
  --max-nodes=7 \
  --min-nodes=1 \
  --no-enable-legacy-authorization

# Create node-pool for Strimzi resources.
# `gcloud beta` required to init taints as of 04/10/18
# https://cloud.google.com/kubernetes-engine/docs/how-to/node-taints
gcloud beta container node-pools create kafka-pool-0 \
  --num-nodes 3 \
  --image-type cos \
  --cluster=$CLUSTER_NAME \
  --machine-type n1-highmem-2  \
  --node-labels=dedicated=StrimziKafka \
  --node-taints=dedicated=StrimziKafka:NoSchedule

## Wait for clusters to come up
echo "Waiting for cluster to become stable before continuing with the installation....."
gcloud compute instance-groups managed list | grep gke-$CLUSTER_NAME | awk '/'$my_name'/ {print $1}' | while read -r line ; do
  gcloud compute instance-groups managed wait-until-stable $line
done

# Switch to new cluster
gcloud container clusters get-credentials $CLUSTER_NAME

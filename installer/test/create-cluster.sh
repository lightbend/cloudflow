#!/bin/bash
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
  --cluster-version 1.14.8-gke.12 \
  --image-type cos \
  --machine-type n1-standard-8 \
  --num-nodes 5 \
  --enable-stackdriver-kubernetes \
  --enable-autoscaling \
  --max-nodes=7 \
  --min-nodes=1 \
  --no-enable-legacy-authorization \
  --project=bubbly-observer-178213


## Wait for clusters to come up
echo "Waiting for cluster to become stable before continuing with the installation....."
gcloud compute instance-groups managed list | grep gke-$CLUSTER_NAME | awk '/'$my_name'/ {print $1}' | while read -r line ; do 
  gcloud compute instance-groups managed wait-until-stable $line
done

# Switch to new cluster
gcloud container clusters get-credentials $1

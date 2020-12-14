#!/bin/sh

cluster_name=$1

echo "Deleting $cluster_name"
gcloud --quiet container clusters delete "${cluster_name}"
gcloud compute disks list --format="table[no-heading](name)" --filter="name~^gke-${cluster_name}" | xargs -n1 gcloud --quiet compute disks delete

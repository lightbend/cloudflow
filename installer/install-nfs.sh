#!/usr/bin/env bash

. common/helm.sh

init_helm
helm repo add stable https://kubernetes-charts.storage.googleapis.com/
helm repo update
helm upgrade nfs-server-provisioner stable/nfs-server-provisioner \
  --install \
  --set storageClass.provisionerName=cloudflow-nfs

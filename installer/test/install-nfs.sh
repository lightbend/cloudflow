#!/usr/bin/env bash
set -e

currentDirectory=$(dirname "$0")

kubectl apply -f "$currentDirectory/nfs.yaml"

helm upgrade cloudflow-nfs stable/nfs-server-provisioner \
--install \
--namespace "default" \
--set createStorage=false \
--set serviceAccount.create=false \
--set serviceAccount.name=nfs-controller

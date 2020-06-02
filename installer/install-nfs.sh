#!/usr/bin/env bash

. common/helm.sh

init_helm
helm upgrade nfs-server-provisioner stable/nfs-server-provisioner --install

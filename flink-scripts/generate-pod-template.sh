#!/bin/bash

STREAMLET_FOLDER=$1
if [ -z "$STREAMLET_FOLDER" ]; then
    echo "No streamlet folder specified."
    exit 1
fi

secret_name=$(jq -rc '.secret_name' ${STREAMLET_FOLDER}streamlet.json)

mkdir -p "${STREAMLET_FOLDER}/output"

rm -rf "${STREAMLET_FOLDER}output/kubernetes"
cp -r "${PWD}/kubernetes" "${STREAMLET_FOLDER}output/kubernetes"

BASE_STAGE0="${STREAMLET_FOLDER}output/kubernetes/stage0/base-stage0.json"
STAGE0="${STREAMLET_FOLDER}output/kubernetes/stage0/stage0.json"

pvc_name="not-exists"
pvc_claim_name="not-exists"
# find the attached PVC
jq -rc '.kubernetes.pods.pod.containers.container."volume-mounts" | keys[]' "${STREAMLET_FOLDER}secrets/pods-config.conf" | \
  while IFS='' read volume_name; do
    echo "Volume name: $volume_name"

    is_pvc=$(jq -rc ".kubernetes.pods.pod.volumes.${volume_name}.pvc" "${STREAMLET_FOLDER}secrets/pods-config.conf")
    if [ -z $is_pvc ] || [ "$is_pvc" = "null" ] || [ "$is_pvc" = "" ]; then
      # Not a PVC
      true
    else
      pvc_name="$volume_name"
      pvc_claim_name=$(jq -r ".kubernetes.pods.pod.volumes.${volume_name}.pvc.name" "${STREAMLET_FOLDER}secrets/pods-config.conf")

      # Write the ouput file and exit
      # TODO improve especially error handling
      jq -r ".spec.volumes[0].secret.secretName = \"${secret_name}\" | .spec.volumes[1].name = \"${pvc_name}\" | .spec.volumes[1].persistentVolumeClaim.claimName = \"${pvc_claim_name}\"" "${BASE_STAGE0}" \
        > "${STAGE0}"

      kubectl kustomize "${STREAMLET_FOLDER}output/kubernetes/stage0" > "${STREAMLET_FOLDER}output/pod-template.yaml"
    fi
  done


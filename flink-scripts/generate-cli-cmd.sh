#!/bin/bash

STREAMLET_FOLDER=$1
if [ -z "$STREAMLET_FOLDER" ]; then
    echo "No streamlet folder specified."
    exit 1
fi

APPLICATION=$2
if [ -z "$APPLICATION" ]; then
    echo "No application name specified."
    exit 1
fi

SERVICE_ACCOUNT=$3
if [ -z "$SERVICE_ACCOUNT" ]; then
    echo "No service account specified."
    exit 1
fi

cluster_id=$(jq -rc '.name' ${STREAMLET_FOLDER}streamlet.json | sed s'/\./\-/')
docker_image=$(jq -rc '.image' ${STREAMLET_FOLDER}streamlet.json)

mkdir -p "${STREAMLET_FOLDER}output"
OUTPUT_CMD="${STREAMLET_FOLDER}output/cli-cmd.sh"

cat > "${OUTPUT_CMD}" << EOF
  flink run-application \\
    --target kubernetes-application \\
    -Dkubernetes.cluster-id=${cluster_id} \\
    -Dkubernetes.service-account=${SERVICE_ACCOUNT} \\
    -Dkubernetes.container.image=${docker_image} \\
    -Dkubernetes.namespace=${APPLICATION} \\
    -Dparallelism.default=2 \\
    -Dhigh-availability=org.apache.flink.kubernetes.highavailability.KubernetesHaServicesFactory \\
    -Dhigh-availability.storageDir=/mnt/flink/storage/ksha \\
    -Dkubernetes.pod-template-file=output/pod-template.yaml \\
    local:///opt/flink/usrlib/cloudflow-runner.jar
EOF
chmod a+x "${OUTPUT_CMD}"

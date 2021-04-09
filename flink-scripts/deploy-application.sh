#! /bin/bash

# Requisites:
# - bash
# - jq
# - kubectl (a recent one)
# - flink CLI on the PATH

APPLICATION=$1
if [ -z "$APPLICATION" ]; then
    echo "No application name specified."
    exit 1
fi

SERVICE_ACCOUNT=$2
if [ -z "$SERVICE_ACCOUNT" ]; then
    echo "No service account specified."
    exit 1
fi

./fetch-streamlets.sh "${APPLICATION}"

./foreach-streamlet.sh swiss-knife ./generate-cli-cmd.sh "${SERVICE_ACCOUNT}"
./foreach-streamlet.sh swiss-knife ./generate-pod-template.sh

./foreach-streamlet.sh swiss-knife ./create-streamlet.sh

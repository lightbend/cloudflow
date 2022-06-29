#!/bin/bash

SERVICEACCCOUNT=$1
if [ -z "$SERVICEACCCOUNT" ]; then
    echo "No serviceaccount name specified."
    exit 1
fi

NAMESPACE=$2
if [ -z "$NAMESPACE" ]; then
    echo "No namespace name specified."
    exit 1
fi

kubectl create serviceaccount "${SERVICEACCCOUNT}" --namespace "${NAMESPACE}"
kubectl create clusterrolebinding "${SERVICEACCCOUNT}-crb" --clusterrole=edit --serviceaccount="${NAMESPACE}:${SERVICEACCCOUNT}"

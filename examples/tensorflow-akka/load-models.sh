#!/bin/bash
# TODO improve this script! the models need to exist before the app is deployed.
# This script loads the necessary files into the PVC that is mounted by the model server
# For this to work the application has to be deployed and all pods need to have entered `running` state

# TODO question, does this work when the pod is scaled?
# name of the streamlet in the blueprint
streamletName="model-server"
podName=$(kubectl get pods -n tensorflow-akka -l com.lightbend.cloudflow/streamlet-name=$streamletName --output jsonpath={.items..metadata.name})
if [ $? -ne 0 ]; then
    echo "Could not find the streamlet `$streamletName` which contains the mounted PVC this script will copy test files into."
    echo "Make sure that the application has been deployed and all pods are running."
    exit 1
fi

echo "Copying files to /models in pod $podName"
kubectl cp models/* -n tensorflow-akka $podName:/models

echo "Done"

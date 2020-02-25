#!/bin/bash

# This script loads the necessary files into the PVC that is mounted by the file based ingress
# For this to work the application has to be deployed and all pods need to have entered `running` state

# name of the streamlet in te blueprint
streamletName="file-ingress"
podName=$(kubectl get pods -n sensor-data-scala -l com.lightbend.cloudflow/streamlet-name=$streamletName --output jsonpath={.items..metadata.name})
if [ $? -ne 0 ]; then
    echo "Could not find the streamlet `$streamletName` which contains the mounted PVC this script will copy test files into."
    echo "Make sure that the application has been deployed and all pods are running."
    exit 1
fi

echo "Copying files to /mnt/data in pod $podName"
kubectl cp test-data/04-moderate-breeze.json -n sensor-data-scala $podName:/mnt/data
kubectl cp test-data/10-storm.json -n sensor-data-scala $podName:/mnt/data
kubectl cp test-data/11-violent-storm.json -n sensor-data-scala $podName:/mnt/data
kubectl cp test-data/12-hurricane.json -n sensor-data-scala $podName:/mnt/data
kubectl cp test-data/invalid-metric.json -n sensor-data-scala $podName:/mnt/data

echo "Done"

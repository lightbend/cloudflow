#!/bin/bash

# This script loads the test file containing device ids that should be filtered out by the pipeline.
# For this to work the application has to be deployed and all pods need to have entered `running` state

streamletName="filter"
podName=$(kubectl get pods -n sensor-data-java -l com.lightbend.cloudflow/streamlet-name=$streamletName --output jsonpath={.items..metadata.name})
if [ $? -ne 0 ]; then
    echo "Could not find the streamlet `$streamletName` which contains the mounted PVC this script will copy the filter file into."
    echo "Make sure that the application has been deployed and all pods are running."
    exit 1
fi

echo "Copying files to /mnt/data in pod $podName"
kubectl cp test-data/device-ids.txt -n sensor-data-java $podName:/mnt/data

echo "Done"

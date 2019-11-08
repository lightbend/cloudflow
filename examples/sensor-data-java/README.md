# `sensor-data-java`

A simple Java based pipeline that ingests, converts, and filters data

# Required configuration

The application requires a persistent volume claim (PVC) to be created before deployment. This PVC is mounted by the `FilterStreamlet` pod, which checks the mounted directory for a configuration file containing device ids that should be filtered out from the data stream.

Example PVC:

```
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: source-data-claim
  namespace: sensor-data-java
spec:
  accessModes:
    - ReadWriteMany
  resources:
    requests:
      storage: 10Mi
```

# Upload device id filter list

The filter streamlet will read a configuration file from the mounted volume. The file should contain the device ids that should be filtered out, with one device id per line. If the file is empty or does not exist, all device ids are accepted.

To upload a prepared file that will filter out one device id (c75cb448-df0e-4692-8e06-0321b7703992), run the following script.

    ./load-data-into-pvc.sh

The file uploaded is named `test-data/device-ids.txt`.

# Generate data

To send data to the HTTP ingress, do the following:

- Get `sensor-data` ingress HTTP endpoint with `kubectl cloudflow status sensor-data-java`

In the example output below the HTTP endpoint would be `docker-registry-default.my.kubernetes.cluster/sensor-data`:

```
kubectl cloudflow status sensor-data-java
Name:             sensor-data-java
Namespace:        sensor-data-java
Version:          445-fcd70ca
Created:          2019-08-20 11:24:54 +0200 CEST
Status:           Running

STREAMLET         ENDPOINT          
sensor-data       docker-registry-default.my.kubernetes.cluster/sensor-data

STREAMLET         POD                                          STATUS            RESTARTS          READY             
metrics           sensor-data-java-metrics-67bc5c45f7-7v5p9    Running           0                 True
sensor-data       sensor-data-java-sensor-data-f8fb77d85-bgtb9 Running           0                 True
filter            sensor-data-java-filter-667d85d44b-8ltmg     Running           0                 True
validation        sensor-data-java-validation-7754885f99-h4l67 Running           0                 True
```

- Pick a test data file from `./test-data`, for example `test-data/04-moderate-breeze.json`
- Send the file to the HTTP endpoint of the ingress using the following `curl` command


    curl -i -X POST sensor-data-java.robert-test.ingestion.io/sensor-data -H "Content-Type: application/json" --data '@test-data/04-moderate-breeze.json'

### Example Deployment example on GKE

TODO:

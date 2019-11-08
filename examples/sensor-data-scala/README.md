# `sensor-data-scala`

A simple pipeline that processes events from a wind turbine farm.

# Required configuration

`valid-logger.log-level`

Log level for `*-logger` streamlets to log to.  Ex) `info`

`valid-logger.msg-prefix` - Log line prefix for `*-logger` streamlets to include.  Ex) `VALID`

kubectl-cloudflow deploy docker-registry-default.purplehat.lightbend.com/lightbend/sensor-data-scala:382-55e76fe-dirty valid-logger.log-level=info valid-logger.msg-prefix=VALID

# Generating data

This example has two ingresses that are combined using a merge operation. Data can be sent to either of the ingresses or to both.

First deploy the app with `kubectl cloudflow deploy [image]`

To send data to the HTTP ingress, do the following:

- Get `sensor-data` ingress HTTP endpoint with `kubectl cloudflow status sensor-data-scala`

In the example output below the HTTP endpoint would be `docker-registry-default.my.kubernetes.cluster/sensor-data-http-ingress`:

```
kubectl cloudflow status sensor-data-scala
Name:             sensor-data-scala
Namespace:        sensor-data-scala
Version:          445-fcd70ca
Created:          2019-08-20 13:55:35 +0200 CEST
Status:           Running

STREAMLET                ENDPOINT          
http-ingress             docker-registry-default.my.kubernetes.cluster/sensor-data-http-ingress

STREAMLET                POD                                                         STATUS            RESTARTS          READY             
invalid-logger           sensor-data-scala-invalid-logger-854dd5b47b-rhg7p           Running           0                 True
http-ingress             sensor-data-scala-http-ingress-6b7c586d6-jtd9x              Running           0                 True
rotor-avg-logger         sensor-data-scala-rotor-avg-logger-86c44d896-4f4gb          Running           0                 True
metrics                  sensor-data-scala-metrics-f6f749d48-n7qss                   Running           0                 True
file-ingress             sensor-data-scala-file-ingress-7f5b966755-jtbnv             Running           0                 True
validation               sensor-data-scala-validation-6f4b59b678-dd4gg               Running           0                 True
rotorizer                sensor-data-scala-rotorizer-55956cb47b-l7kng                Running           0                 True
merge                    sensor-data-scala-merge-548994576-k8k8h                     Running           0                 True
valid-logger             sensor-data-scala-valid-logger-86449cb958-wztsq             Running           0                 True
```

- Pick a test data file from `./test-data`, for example `test-data/04-moderate-breeze.json`
- Send the file to the HTTP ingress using `curl` using following command


    curl -i -X POST sensor-data-scala.apps.purplehat.lightbend.com/sensor-data -H "Content-Type: application/json" --data '@test-data/04-moderate-breeze.json'

To send data to the file ingress, use the following shell script found in the project root directory:

    ./load-data-into-pvc.sh

The shell script will load a number of files from the `test-data` directory and the ingress will continuously read those files and emit their content to the merge streamlet.

## Using [`wrk`](https://github.com/wg/wrk) benchmarking tool

To send a continuous stream of data.

### Install

* Ubuntu: `apt-get install wrk`
* MacOS: `brew install wrk`

### Run

Ex)

```
wrk -c 400 -t 400 -d 500 -s wrk-04-moderate-breeze.lua http://sensor-data-scala.apps.purplehat.lightbend.com/sensor-data
```

### Example Deployment example on GKE

TODO:

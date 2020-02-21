## `sensor-data-scala`

### Problem Definition

A simple pipeline that processes events from a wind turbine farm.

### Required configuration

`valid-logger.log-level`

Log level for `*-logger` streamlets to log to.  Ex) `info`

`valid-logger.msg-prefix` - Log line prefix for `*-logger` streamlets to include.  Ex) `VALID`

### Generating data

This example has two ingresses that are combined using a merge operation. Data can be sent to either of the ingresses or to both.

- Pick a test data file from `./test-data`, for example `test-data/04-moderate-breeze.json`
- Send the file to the HTTP ingress using `curl` (see deployment example later on).

To send data to the file ingress, use the following shell script found in the project root directory:

    ./load-data-into-pvc.sh

The shell script will load several files from the `test-data` directory and the ingress will continuously read those files and emit their content to the merge streamlet:

```
$ ./load-data-into-pvc.sh
Copying files to /mnt/data in pod sensor-data-scala-file-ingress-7d6df5c4cb-hlwmz
Done
```

#### Using [`wrk`](https://github.com/wg/wrk) benchmarking tool

To send a continuous stream of data.

##### Install

* Ubuntu: Follow the instructions [here](https://github.com/wg/wrk/wiki/Installing-Wrk-on-Linux).
* MacOS: `brew install wrk`

##### Run

Ex)

```
wrk -c 400 -t 400 -d 500 -s wrk-04-moderate-breeze.lua <http-ingress-url>
```

### Example Deployment on Kubernetes

* Make sure you have installed a Kubernetes cluster with Cloudflow running as per the [installation guide](https://github.com/lightbend/cloudflow-installer).

* Verify GKE cluster and Google docker registry 

```
$ gcloud container clusters get-credentials <CLUSTER_NAME>
$ gcloud auth configure-docker
```

* Verify EKS cluster and ECR docker registry
```
$ aws eks describe-cluster <CLUSTER_NAME>
$ eval $(aws ecr get-login --no-include-email --region us-east-1)
```

* Add the docker registry to your sbt project (should be adjusted to your setup). The following lines should be there in the file `target-env.sbt` at the root of your application. e.g.

```
ThisBuild / cloudflowDockerRegistry := Some("eu.gcr.io")
ThisBuild / cloudflowDockerRepository := Some("my-awesome-project")
```

`my-awesome-project` refers to the project ID of your Google Cloud Platform project.

* Build the application:

```
$ sbt buildAndPublish
...
[info] You can deploy the application to a Kubernetes cluster using any of the the following commands:
[info]  
[info]   kubectl cloudflow deploy eu.gcr.io/<projectID>/sensor-data-scala:8-2a0f65d-dirty
[info]  
[success] Total time: 19 s, completed Nov 25, 2019 11:22:53 AM

```

* Make sure you have the `kubectl cloudflow` plugin configured.

```
$ kubectl cloudflow help
This command line tool can be used to deploy and operate Cloudflow applications.
...
```

* Create the application namespace

```
$ kubectl create ns sensor-data-scala
```

Install the required PVC for the file ingress (This is optional as you can remove the file ingress from the blueprint
and use only the HTTP ingress for posting data.)

* Install PVC on GKE
```
kubectl apply -f templates/nfs.yaml -n sensor-data-scala
```

* Install PVC on EKS
```
kubectl apply -f templates/efs.yaml -n sensor-data-scala
```

* Deploy the app to a GKE cluster

```
$ kubectl cloudflow deploy -u oauth2accesstoken --volume-mount file-ingress.source-data-mount=pv-volume eu.gcr.io/<projectID>/sensor-data-scala:8-2a0f65d-dirty -p "$(gcloud auth print-access-token)"
```

* Deploy the app to an EKS cluster

```
$ kubectl cloudflow deploy -u $(aws iam get-user | jq -r .User.UserName) --volume-mount file-ingress.source-data-mount=pv-volume index.docker.io/<user>/sensor-data-scala:8-2a0f65d-dirty -p "<docker_hub_password>"
```

* Verify that the application is deployed.

```
$ kubectl cloudflow list

NAME              NAMESPACE         VERSION           CREATION-TIME     
sensor-data-scala sensor-data-scala 8-2a0f65d-dirty   2019-11-25 13:22:44 +0200 EET
```

* Check all pods are running.

```
$ kubectl get pods -n sensor-data-scala
NAME                                                  READY   STATUS    RESTARTS   AGE
sensor-data-scala-file-ingress-7d6df5c4cb-q28rx       1/1     Running   0          97s
sensor-data-scala-http-ingress-747895bf7d-drb4x       1/1     Running   0          98s
sensor-data-scala-invalid-logger-5d7dc9964b-7bkjz     1/1     Running   0          99s
sensor-data-scala-merge-c55787cb4-b4qk5               1/1     Running   0          98s
sensor-data-scala-metrics-759cf5f8fd-9bdk4            1/1     Running   0          99s
sensor-data-scala-rotor-avg-logger-85b7c456fd-5bbbn   1/1     Running   0          98s
sensor-data-scala-rotorizer-7f7794c84-xxvrf           1/1     Running   0          98s
sensor-data-scala-valid-logger-84fff7468d-jttrp       1/1     Running   0          97s
sensor-data-scala-validation-9c765d79b-rzr97          1/1     Running   0          99s
```

* Verify the application output

Access the HTTP ingress (no public ingress is available by default):

```
kubectl port-forward sensor-data-scala-http-ingress-747895bf7d-drb4x -n sensor-data-scala 3003:3003
```

Post data to the HTTP ingress:

```
$ cat test-data/invalid-metric.json
{
    "deviceId": "c75cb448-df0e-4692-8e06-0321b7703992",
    "timestamp": 1495545346279,
    "measurements": {
        "power": -1.7,
        "rotorSpeed": 3.9,
        "windSpeed": 25.3
    }
}

$ curl -i -X POST localhost:3003 -H "Content-Type: application/json" --data '@test-data/invalid-metric.json'
HTTP/1.1 202 Accepted
Server: akka-http/10.1.11
Date: Mon, 25 Nov 2019 10:29:37 GMT
Content-Type: text/plain; charset=UTF-8
Content-Length: 88

The request has been accepted for processing, but the processing has not been completed.

```

Verify that the application has processed the invalid record:

```
$ kubectl logs sensor-data-scala-invalid-logger-5d7dc9964b-7bkjz -n sensor-data-scala
Running Akka entrypoint script
Pipelines Runner
...
[WARN] [11/25/2019 10:29:39.274] [akka_streamlet-akka.actor.default-dispatcher-4] [akka.actor.ActorSystemImpl(akka_streamlet)] Invalid metric detected! {"metric": {"deviceId": "c75cb448-df0e-4692-8e06-0321b7703992", "timestamp": 1495545346279, "name": "power", "value": -1.7}, "error": "All measurements must be positive numbers!"}
```

Note: This application prints to console using log level WARN. If you want to check the valid metrics you need to deploy
by changing the application log level at deployment time:

```
kubectl cloudflow  deploy -u oauth2accesstoken --volume-mount file-ingress.source-data-mount=file-ingress.source-data-mount eu.gcr.io/<projectID>/sensor-data-scala:8-2a0f65d-dirty -p "$(gcloud auth print-access-token)" valid-logger.log-level=info valid-logger.msg-prefix=valid
```

The application uses Akka system log which by default it has log level _Warning_.

Then you can verify the valid log entries:

```
cat test-data/04-moderate-breeze.json
{
   "deviceId": "c75cb448-df0e-4692-8e06-0321b7703992",
   "timestamp": 1495545346279,
   "measurements": {
       "power": 1.7,
       "rotorSpeed": 3.9,
       "windSpeed": 25.3
   }
}

$ curl -i -X POST localhost:3003 -H "Content-Type: application/json" --data '@test-data/04-moderate-breeze.json'
HTTP/1.1 202 Accepted
Server: akka-http/10.1.11
Date: Mon, 25 Nov 2019 11:23:14 GMT
Content-Type: text/plain; charset=UTF-8
Content-Length: 88

The request has been accepted for processing, but the processing has not been completed.

$ kubectl logs sensor-data-scala-valid-logger-84fff7468d-dd72t  -n sensor-data-scala
Running Akka entrypoint script
Pipelines Runner
...
$
[INFO] [11/25/2019 11:23:16.020] [akka_streamlet-akka.actor.default-dispatcher-2] [akka.actor.ActorSystemImpl(akka_streamlet)] valid {"deviceId": "c75cb448-df0e-4692-8e06-0321b7703992", "timestamp": 1495545346279, "name": "rotorSpeed", "value": 3.9}
[INFO] [11/25/2019 11:23:16.021] [akka_streamlet-akka.actor.default-dispatcher-2] [akka.actor.ActorSystemImpl(akka_streamlet)] valid {"deviceId": "c75cb448-df0e-4692-8e06-0321b7703992", "timestamp": 1495545346279, "name": "windSpeed", "value": 25.3}
[INFO] [11/25/2019 11:23:16.027] [akka_streamlet-akka.actor.default-dispatcher-2] [akka.actor.ActorSystemImpl(akka_streamlet)] valid {"deviceId": "c75cb448-df0e-4692-8e06-0321b7703992", "timestamp": 1495545346279, "name": "power", "value": 1.7}
```

* Undeploy.

```
$ kubectl cloudflow undeploy sensor-data-scala
```

## `sensor-data-java`

### Problem Definition

A simple pipeline that processes events from a wind turbine farm.

Note: This app has very limited functionality (eg. supports only filtering of events) compared to the more advanced Scala version of it, which can be found under the [sensor-data-scala](../sensor-data-scala) directory.

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
./load-data-into-pvc.sh
Copying files to /mnt/data in pod sensor-data-java-filter-6bd89bd94-lhqvq
Done
```

### Using [`wrk`](https://github.com/wg/wrk) benchmarking tool

To send a continuous stream of data.

#### Install

* Ubuntu: Follow the instructions [here](https://github.com/wg/wrk/wiki/Installing-Wrk-on-Linux).
* MacOS: `brew install wrk`

#### Run

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
[info]   kubectl cloudflow deploy eu.gcr.io/<projectID>/sensor-data-java:9-bbdec44-dirty
[info]  
[success] Total time: 27 s, completed Nov 26, 2019 12:54:41 PM

```

* Make sure you have the `kubectl cloudflow` plugin configured.

```
$ kubectl cloudflow help
This command line tool can be used to deploy and operate Cloudflow applications.
...
```

* Create the application namespace

```
$ kubectl create ns sensor-data-java
```

Install the required PVC for the file ingress (This is optional as you can remove the file ingress from the blueprint
and use only the HTTP ingress for posting data.)

* Install PVC on GKE
```
kubectl apply -f templates/nfs.yaml -n sensor-data-java
```

* Install PVC on EKS
```
kubectl apply -f templates/efs.yaml -n sensor-data-java
```

* Deploy the app to a GKE cluster

```
$ kubectl cloudflow  deploy -u oauth2accesstoken --volume-mount filter.configuration=pv-volume eu.gcr.io/<projectID>/sensor-data-java:9-bbdec44-dirty -p "$(gcloud auth print-access-token)" valid-logger.log-level=info valid-logger.msg-prefix=valid
```

* Deploy the app to an EKS cluster

```
$ kubectl cloudflow deploy -u $(aws iam get-user | jq -r .User.UserName) --volume-mount file-ingress.source-data-mount=pv-volume index.docker.io/<user>/sensor-data-scala:8-2a0f65d-dirty -p "<docker_hub_password>"
```

Note: The application uses Akka system log which by default it has log level _Warning_.
We have deployed with log level _info_ so we can see the valid entries on the console.

* Verify that the application is deployed.

```
$ kubectl cloudflow list

NAME              NAMESPACE         VERSION           CREATION-TIME     
sensor-data-java  sensor-data-java  9-bbdec44-dirty   2019-11-26 13:58:55 +0200 EET
```

* Check all pods are running.

```
$ kubectl get pods -n sensor-data-java
NAME                                            READY   STATUS    RESTARTS   AGE
sensor-data-java-filter-6bd89bd94-lhqvq         1/1     Running   0          93s
sensor-data-java-metrics-6db886c9f8-8xt4d       1/1     Running   0          94s
sensor-data-java-sensor-data-845f4dcdc4-g9rs9   1/1     Running   0          93s
sensor-data-java-validation-59cb8fbb56-rln6l    1/1     Running   0          94s
```

* Posting data

Access the HTTP ingress (no public ingress is available by default):

```
$ kubectl port-forward sensor-data-java-sensor-data-845f4dcdc4-g9rs9 -n sensor-data-java 3002:3002

$ cat test-data/04-moderate-breeze.json
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
```

This application does not log processed data. For a more complete application check the Scala version
[here](../sensor-data-scala/README.md).

* Undeploy.

```
$ kubectl cloudflow undeploy sensor-data-java
```

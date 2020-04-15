## Akka Cluster Sharding Cloudflow Application

### Problem Definition

This project clusters a set of Akka Streamlets to demonstrate how leverage
Akka Cluster Sharding for stateful stream processing in Cloudflow

![](akka-cluster-streams.png)

### Sub projects

This application consists of the following sub-projects:

* `akka-connected-car`: Contains the blueprint
* `akka-connected-car-streamlet`: Akka Streams based ingress, cluster streamlet, and logger
* `datamodel`: Contains the Avro schema

### Example Deployment example on GKE

**Steps:**

* Make sure you have installed a GKE cluster with Cloudflow running as per the [installation guide](https://github.com/lightbend/cloudflow-installer).
Make sure you have access to your cluster:

```
gcloud container clusters get-credentials <CLUSTER_NAME>
```

and that you have access to the Google docker registry:

```
gcloud auth configure-docker
```

* Add the Google docker registry to your sbt project (should be adjusted to your setup). The following lines should be there in the file `target-env.sbt` at the root of your application. e.g.

```
ThisBuild / cloudflowDockerRegistry := Some("eu.gcr.io")
ThisBuild / cloudflowDockerRepository := Some("my-awesome-project")
```

`my-awesome-project` refers to the project ID of your Google Cloud Platform project.

* Build the application:

```
$ sbt buildAndPublish
```

At the very end you should see the application image built and instructions for how to deploy it:

```
[info] 221-65c1693-dirty: digest: sha256:3377f15b53ad46781dd2396c077dff2c78d8f4ccd102d272ce97d5af7b8fcd86 size: 6804
[info]
[info] Successfully built and published the following Cloudflow application image:
[info]
[info]   eu.gcr.io/my-awesome-project/connected-car-akka-cluster:221-65c1693-dirty
[info]
[info] You can deploy the application to a Kubernetes cluster using any of the the following commands:
[info]
[info]   kubectl cloudflow deploy eu.gcr.io/my-awesome-project/connected-car-akka-cluster:221-65c1693-dirty
[info]
[success] Total time: 317 s (05:17), completed Apr 14, 2020 10:30:03 PM
```

* Make sure you have the `kubectl cloudflow` plugin configured.

```
$ kubectl cloudflow help
This command line tool can be used to deploy and operate Cloudflow applications.
...
```

* Deploy the app.

```
$ kubectl cloudflow deploy -u oauth2accesstoken eu.gcr.io/my-awesome-project/connected-car-akka-cluster:221-65c1693-dirty -p $(gcloud auth print-access-token)"
WARNING! Using --password via the CLI is insecure. Use --password-stdin.
[Done] Deployment of application `connected-car-akka-cluster` has started.

```

*  Verify it is deployed correctly.

```
$ kubectl cloudflow list

NAME                       NAMESPACE                  VERSION           CREATION-TIME
connected-car-akka-cluster connected-car-akka-cluster 221-65c1693-dirty 2020-04-14 22:35:44 -0500 CDT
```

* Check all pods are running.

```
$ kubectl cloudflow list

NAME                       NAMESPACE                  VERSION           CREATION-TIME
connected-car-akka-cluster connected-car-akka-cluster 221-65c1693-dirty 2020-04-14 22:35:44 -0500 CDT
nolangrace@nolans-MBP-2 ~ $ kubectl cloudflow status connected-car-akka-cluster
Name:             connected-car-akka-cluster
Namespace:        connected-car-akka-cluster
Version:          221-65c1693-dirty
Created:          2020-04-14 22:35:44 -0500 CDT
Status:           Running

STREAMLET         POD                                                     READY             STATUS            RESTARTS
car-cluster       connected-car-akka-cluster-car-cluster-5695d7bbc6-czrlh 1/1               Running           0
car-data          connected-car-akka-cluster-car-data-78b469856d-csx9b    1/1               Running           0
car-printer       connected-car-akka-cluster-car-printer-864b9d675b-6hrzj 1/1               Running           0
```

* Verify the application output.

```
$ kubectl -n connected-car-akka-cluster logs -f connected-car-akka-cluster-car-cluster-5695d7bbc6-czrlh
...
[INFO] [04/15/2020 03:36:27.391] [akka_streamlet-akka.actor.default-dispatcher-15] [akka.tcp://akka_streamlet@10.28.5.30:2551/system/sharding/Counter/1/10001001] Updated CarId: Car-10001001 Driver Name: Duncan CarSpeed: 60.0 From Actor:akka://akka_streamlet/temp/$I
[INFO] [04/15/2020 03:36:28.423] [akka_streamlet-akka.actor.default-dispatcher-21] [akka.tcp://akka_streamlet@10.28.5.30:2551/system/sharding/Counter/8/10001008] Updated CarId: Car-10001008 Driver Name: Hywel CarSpeed: 81.0 From Actor:akka://akka_streamlet/temp/$J
[INFO] [04/15/2020 03:36:29.454] [akka_streamlet-akka.actor.default-dispatcher-3] [akka.tcp://akka_streamlet@10.28.5.30:2551/system/sharding/Counter/8/10001008] Updated CarId: Car-10001008 Driver Name: Hywel CarSpeed: 64.0 From Actor:akka://akka_streamlet/temp/$K
[INFO] [04/15/2020 03:36:30.387] [akka_streamlet-akka.actor.default-dispatcher-17] [akka.tcp://akka_streamlet@10.28.5.30:2551/system/sharding/Counter/5/10001005] Updated CarId: Car-10001005 Driver Name: David CarSpeed: 60.0 From Actor:akka://akka_streamlet/temp/$L
[INFO] [04/15/2020 03:36:31.413] [akka_streamlet-akka.actor.default-dispatcher-21] [akka.tcp://akka_streamlet@10.28.5.30:2551/system/sharding/Counter/5/10001005] Updated CarId: Car-10001005 Driver Name: David CarSpeed: 81.0 From Actor:akka://akka_streamlet/temp/$M
[INFO] [04/15/2020 03:36:32.433] [akka_streamlet-akka.actor.default-dispatcher-15] [akka.tcp://akka_streamlet@10.28.5.30:2551/system/sharding/Counter/2/10001002] Updated CarId: Car-10001002 Driver Name: Kiki CarSpeed: 61.0 From Actor:akka://akka_streamlet/temp/$N
[INFO] [04/15/2020 03:36:33.387] [akka_streamlet-akka.actor.default-dispatcher-17] [akka.tcp://akka_streamlet@10.28.5.30:2551/system/sharding/Counter/5/10001005] Updated CarId: Car-10001005 Driver Name: David CarSpeed: 86.0 From Actor:akka://akka_streamlet/temp/$O
[INFO] [04/15/2020 03:36:34.403] [akka_streamlet-akka.actor.default-dispatcher-21] [akka.tcp://akka_streamlet@10.28.5.30:2551/system/sharding/Counter/3/10001003] Updated CarId: Car-10001003 Driver Name: Trevor CarSpeed: 79.0 From Actor:akka://akka_streamlet/temp/$P
[INFO] [04/15/2020 03:36:35.433] [akka_streamlet-akka.actor.default-dispatcher-21] [akka.tcp://akka_streamlet@10.28.5.30:2551/system/sharding/Counter/1/10001001] Updated CarId: Car-10001001 Driver Name: Duncan CarSpeed: 90.0 From Actor:akka://akka_streamlet/temp/$Q
```

Scale Cluster Streamlet
```
$ kubectl cloudflow scale connected-car-akka-cluster car-cluster 3
```

* Undeploy.

```
$ kubectl cloudflow undeploy connected-car-akka-cluster
```

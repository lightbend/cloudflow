## Akka and Spark-based Cloudflow Application


### Problem Definition

This application aggregates streaming data for phone call records. It's a mixed Cloudflow application consisting of Akka and Spark-based streamlets.

### Sub projects

This application consists of the following sub-projects:

* `akka-cdr-ingestor`: Akka streams based data ingestion, merging and validation
* `spark-aggregation`: Spark based aggregation of data coming from the ingestor streamlets
* `akka-java-aggregation-output`: Egress implementation for logging in Java
* `call-record-pipelines`: Contains the blueprint
* `datamodel`: Contains the Avro schema


### Example Deployment example on GKE

**Steps:**

* Make sure you have installed a GKE cluster with Cloudflow running as per the [installation guide](https://github.com/lightbend/cloudflow-installer).
Make sure you have access to your cluster:

```bash
$ gcloud container clusters get-credentials <CLUSTER_NAME>
```

and that you have access to the Google docker registry:

```bash
$ gcloud auth configure-docker
```

* Add the Google docker registry to your sbt project (should be adjusted to your setup). The following lines should be there in the file `target-env.sbt` at the root of your application. e.g.

```
ThisBuild / cloudflowDockerRegistry := Some("eu.gcr.io")
ThisBuild / cloudflowDockerRepository := Some("my-awesome-project")
```

`my-awesome-project` refers to the project ID of your Google Cloud Platform project.

* Build the application:

```bash
$ sbt buildApp
```
At the very end you should see the application image built and instructions for how to deploy it:

```
[info] 467-26acd87-dirty: digest: sha256:77b50070d11d07a030281b1e3f8cff8b8abfa28b6753b98a887a9b7fce541d30 size: 2415
[info]  
[info] Successfully built and published the following image:
[info]   docker.io/lightbend/akka-cdr-ingestor:467-26acd87-dirty
[info] 467-26acd87-dirty: digest: sha256:8659120e1c77a0492267fc087aefaf7adff3ce20b48d1cba913ee2c0d416b2a3 size: 2415
[info]  
[info] Successfully built and published the following image:
[info]   docker.io/lightbend/akka-java-aggregation-output:467-26acd87-dirty
[info] 467-26acd87-dirty: digest: sha256:ee54b9fae8dff754e3c7949d1cc64f49fd8ab242d29e2a98d56228e3eb5daa5e size: 4927
[info]  
[info] Successfully built and published the following image:
[info]   docker.io/lightbend/spark-aggregation:467-26acd87-dirty
[success] Cloudflow application CR generated in /Users/myuser/lightbend-repos/cloudflow/examples/call-record-aggregator/target/call-record-aggregator.json
[success] Use the following command to deploy the Cloudflow application:
[success] kubectl cloudflow deploy /Users/myuser/lightbend-repos/cloudflow/examples/call-record-aggregator/target/call-record-aggregator.json
[success] Total time: 31 s, completed Jun 16, 2020 9:12:19 AM
```

* Make sure you have the `kubectl cloudflow` plugin configured.

```bash
$ kubectl cloudflow help
This command line tool can be used to deploy and operate Cloudflow applications.
...
```

* Deploy the app using the command mentioned in the output above.

```bash
$ kubectl cloudflow deploy /Users/myuser/lightbend-repos/cloudflow/examples/call-record-aggregator/target/call-record-aggregator.json
Existing value will be used for configuration parameter 'cdr-generator2.records-per-second'
Existing value will be used for configuration parameter 'cdr-generator1.records-per-second'
Existing value will be used for configuration parameter 'cdr-aggregator.group-by-window'
Existing value will be used for configuration parameter 'cdr-aggregator.watermark'
[Done] Deployment of application `call-record-aggregator` has started.
```

*  Verify it is deployed correctly.

```bash
$ kubectl cloudflow list

NAME                   NAMESPACE              VERSION           CREATION-TIME     
call-record-aggregator call-record-aggregator 34-69082eb-dirty  2019-11-08 15:46:22 +0000 UTC
```

* Check all pods are running.

```bash
$ kubectl get pods -n call-record-aggregator
NAME                                                         READY   STATUS    RESTARTS   AGE
call-record-aggregator-cdr-aggregator-1573217778868-exec-1   1/1     Running   0          63s
call-record-aggregator-cdr-aggregator-1573217778868-exec-2   1/1     Running   0          63s
call-record-aggregator-cdr-aggregator-driver                 1/1     Running   0          74s
call-record-aggregator-cdr-generator1-1573217778862-exec-1   1/1     Running   0          65s
call-record-aggregator-cdr-generator1-1573217778862-exec-2   1/1     Running   0          65s
call-record-aggregator-cdr-generator1-driver                 1/1     Running   0          75s
call-record-aggregator-cdr-generator2-1573217778679-exec-1   1/1     Running   0          66s
call-record-aggregator-cdr-generator2-1573217778679-exec-2   1/1     Running   0          65s
call-record-aggregator-cdr-generator2-driver                 1/1     Running   0          75s
call-record-aggregator-cdr-ingress-56b4b55b8-9rxwn           1/1     Running   0          80s
call-record-aggregator-cdr-validator-74fc59df74-5p8mz        1/1     Running   0          80s
call-record-aggregator-console-egress-5f6f7777f8-dknt6       1/1     Running   0          80s
call-record-aggregator-error-egress-8858f68-5sjp8            1/1     Running   0          80s
call-record-aggregator-merge-67b66c8fdb-2r247                1/1     Running   0          80s
```

* Verify the application output.

```bash
$ kubectl logs call-record-aggregator-console-egress-5f6f7777f8-dknt6  -n call-record-aggregator
Running Akka entrypoint script
Pipelines Runner
Java opts: -javaagent:/app/prometheus/jmx_prometheus_javaagent-0.11.0.jar=2050:/etc/cloudflow-runner/prometheus.yaml -XX:MaxRAMPercentage=50.0 -Djdk.nio.maxCachedBufferSize=1048576
Classpath: /etc/cloudflow-runner:/opt/cloudflow/*
Loading application.conf from: /etc/cloudflow-runner/application.conf, secret config from: /etc/cloudflow-runner-secret/secret.conf
{"startTime": 1573217760, "windowDuration": 60, "avgCallDuration": 3555900.5973705836, "totalCallDuration": 4327531027}
{"startTime": 1573217760, "windowDuration": 60, "avgCallDuration": 3562492.234764543, "totalCallDuration": 5144238787}
{"startTime": 1573217820, "windowDuration": 60, "avgCallDuration": 3612155.7444444443, "totalCallDuration": 975282051}
{"startTime": 1573217820, "windowDuration": 60, "avgCallDuration": 3657294.1894197953, "totalCallDuration": 2143174395}
{"startTime": 1573217820, "windowDuration": 60, "avgCallDuration": 3569123.699292453, "totalCallDuration": 3026616897}
{"startTime": 1573217820, "windowDuration": 60, "avgCallDuration": 3566416.369058714, "totalCallDuration": 3826764764}
{"startTime": 1573217820, "windowDuration": 60, "avgCallDuration": 3587085.590874525, "totalCallDuration": 4717017552}
```

* Undeploy.

```bash
$ kubectl cloudflow undeploy call-record-aggregator
```

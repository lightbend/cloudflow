## ML Fraud Detection - Akka, Tensorflow, and Apache Spark


### Problem Definition

This application processes credit card transactions through a Tensorflow model to predect potential fraudulent activity.  It is a Cloudflow Application using Spark and Akka streamlets.

### Sub projects

This application consists of the following sub-projects:

* `ml-fraud-detection`: Contains the blueprint
* `akka-streams`: Akka streams based data ingestion, merging and validation
* `model-serving`: Akka based toolkit for serving Tensorflow ML model within Akka actors
* `schema`: Contains the Avro schema
* `spark`: Spark based Windowing and Aggregation of transactions


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
ThisBuild / cloudflowDockerRegistry := Some("gcr.io")
ThisBuild / cloudflowDockerRepository := Some("my-awesome-project")
```

`my-awesome-project` refers to the project ID of your Google Cloud Platform project.

* Build the application:

```
$ sbt buildAndPublish
```
At the very end you should see the application image built and instructions for how to deploy it:

```
[info] 28-6cbecc2-dirty: digest: sha256:f844313c051d8d0eda755c2fbe502594e628a46a9d830261482e5370ebdce770 size: 6806
[info]
[info] Successfully built and published the following Cloudflow application image:
[info]
[info]   gcr.io/gsa-pipeliners/ml-fraud-detection:28-6cbecc2-dirty
[info]
[info] You can deploy the application to a Kubernetes cluster using any of the the following commands:
[info]
[info]   kubectl cloudflow deploy gcr.io/my-awesome-project/ml-fraud-detection:28-6cbecc2-dirty
[info]
[success] Total time: 150 s, completed Dec 4, 2019 4:09:33 PM
```

* Make sure you have the `kubectl cloudflow` plugin configured.

```
$ kubectl cloudflow help
This command line tool can be used to deploy and operate Cloudflow applications.
...
```

* Deploy the app.

```
$ kubectl cloudflow deploy -u oauth2accesstoken eu.gcr.io/my-awesome-project/ml-fraud-detection:28-6cbecc2-dirty -p "$(gcloud auth print-access-token)"
Existing value will be used for configuration parameter 'transaction-generator.data-frequency'
Existing value will be used for configuration parameter 'fraud-detection.ml-model-file-location'
Existing value will be used for configuration parameter 'fraud-detection.ml-model-name'
WARNING! Using --password via the CLI is insecure. Use --password-stdin.
[Done] Deployment of application `call-record-aggregator` has started.

```

*  Verify it is deployed correctly.

```
$ kubectl cloudflow list

NAME               NAMESPACE          VERSION           CREATION-TIME
ml-fraud-detection ml-fraud-detection 28-6cbecc2-dirty  2019-12-04 16:18:20 -0600 CST
```

* Check all pods are running.

```
$ kubectl cloudflow status ml-fraud-detection
Name:             ml-fraud-detection
Namespace:        ml-fraud-detection
Version:          28-6cbecc2-dirty
Created:          2019-12-04 16:18:20 -0600 CST
Status:           Running

STREAMLET              POD                                                        STATUS            RESTARTS          READY
log-fraud-report       ml-fraud-detection-log-fraud-report-6ff4786648-b7fqg       Running           0                 True
transactions-over-http ml-fraud-detection-transactions-over-http-6777f767fd-gv4b9 Running           0                 True
fraud-report           ml-fraud-detection-fraud-report-driver                     Running           0                 True
fraud-report           ml-fraud-detection-fraud-report-1575497904226-exec-1       Running           0                 True
fraud-report           ml-fraud-detection-fraud-report-1575497904226-exec-2       Running           0                 True
transaction-generator  ml-fraud-detection-transaction-generator-58596c757d-fdbvs  Running           0                 True
merge-sources          ml-fraud-detection-merge-sources-69fb55d95b-9gv5f          Running           0                 True
split-on-merchant      ml-fraud-detection-split-on-merchant-57f5bd48c6-nkjjg      Running           0                 True
fraud-detection        ml-fraud-detection-fraud-detection-6f58c47cc4-q2tzk        Running           0                 True
log-transactions       ml-fraud-detection-log-transactions-6589d7b84d-hzswj       Running           0                 True
```

* Verify the application output.

```
$ kubectl logs call-record-aggregator-console-egress-5f6f7777f8-dknt6  -n call-record-aggregator
Running Akka entrypoint script
Pipelines Runner
Java opts: -javaagent:/app/prometheus/jmx_prometheus_javaagent-0.11.0.jar=2050:/etc/cloudflow-runner/prometheus.yaml -XX:MaxRAMPercentage=50.0 -Djdk.nio.maxCachedBufferSize=1048576
Classpath: /etc/cloudflow-runner:/opt/cloudflow/*
Loading application.conf from: /etc/cloudflow-runner/application.conf, secret config from: /etc/cloudflow-runner-secret/secret.conf
19/12/04 22:18:37 INFO LogCustomerTransactions:
Initializing Akkastream Runner ..

+--------------------------------------------------------------------------------+
Build Info
+--------------------------------------------------------------------------------+

Name          : cloudflow-runner
Version       : 1.3.0-M1
Scala Version : 2.12.9
sbt Version   : 1.2.8
Build Time    : 2019-11-20T11:31:48.749Z
Build User    : debasishghosh


19/12/04 22:18:44 INFO LogCustomerTransactions: a051a678-ddbb-4fcf-a13f-19bb63b025d1 Ok
19/12/04 22:18:45 INFO LogCustomerTransactions: fa143a8d-9482-45ef-939f-8466494441d6 Ok
19/12/04 22:18:46 INFO LogCustomerTransactions: bd9726b3-6c7e-41f4-a8d3-b8a5e803b2cb Ok
19/12/04 22:18:47 INFO LogCustomerTransactions: da60e738-d477-48ce-b034-20d990b569ee Ok
19/12/04 22:18:48 INFO LogCustomerTransactions: 4d442a30-4b38-428a-b825-df64c7f39927 Ok
19/12/04 22:18:49 INFO LogCustomerTransactions: a275b8c6-da1f-4d19-9dc6-eb831feea7e4 Ok
19/12/04 22:18:50 INFO LogCustomerTransactions: e2787fe2-10ce-4bd8-a59f-a8ffec8a10a7 Ok
19/12/04 22:18:51 INFO LogCustomerTransactions: ef7eecf2-f0fe-4a6d-990a-cffc226579c8 Ok
19/12/04 22:18:52 INFO LogCustomerTransactions: 32a13bee-1939-4637-97b1-187d12fa3b02 Ok
```

* Update the ML Model

```
$ kubectl cloudflow configure ml-fraud-detection fraud-detection.ml-model-name="another-tensorflow-model" fraud-detection.ml-model-file-location="models/another-tensorflow-model.pb"
Existing value will be used for configuration parameter 'transaction-generator.data-frequency'
[Done] Configuration of application ml-fraud-detection has been updated.
```

* Undeploy.

```
$ kubectl cloudflow undeploy ml-fraud-detection
```


## Spark-based Cloudflow Application

### Problem Definition

In this application we use a an ingress to generate random data from a set of virtual sensors, use Spark to emit moving average values for each sensor id and the report the values to the console egress.

### Example Deployment on GKE

Steps:

* Make sure you have installed a gke cluster and you are running Cloudflow
(check https://github.com/lightbend/cloudflow-installer for more).
Make sure you have access to your cluster:

```
gcloud container clusters get-credentials <CLUSTER_NAME>
```

and that you have access to the Google docker registry:

```
gcloud auth configure-docker
```

* Add the Google docker registry to your sbt project (should be adjusted to your setup). Eg.

```
ThisBuild / cloudflowDockerRegistry := Some("eu.gcr.io")
ThisBuild / cloudflowDockerRepository := Some("my-awesome-project")
```

`my-awesome-project` refers to the project ID of your Google Cloud Platform project.

* Build the application.

```
sbt buildAndPublish
```
At the very end you should see the application image built and instructions for how to deploy it:

```
[info] 34-69082eb-dirty: digest: sha256:25e782e2ef9a9179a9eb53aac3a32c1c05f6a18b4898f47e179d81eb2d8b4d1f size: 6805
[info]  
[info] Successfully built and published the following Cloudflow application image:
[info]  
[info]   eu.gcr.io/my-awesome-project/spark-sensors:34-69082eb-dirty
[info]  
[info] You can deploy the application to a Kubernetes cluster using any of the the following commands:
[info]  
[info]   kubectl cloudflow deploy eu.gcr.io/my-awesome-project/spark-sensors:34-69082eb-dirty
[info]  
[success] Total time: 53 s, completed Nov 8, 2019 1:40:52 PM
```

* Make sure you have the kubectl cloudflow plugin setup.

```
kubectl cloudflow help
This command line tool can be used to deploy and operate Cloudflow applications.
...
```
* Deploy the app.

```
kubectl cloudflow deploy -u oauth2accesstoken  eu.gcr.io/my-awesome-project/spark-sensors:34-69082eb-dirty -p "$(gcloud auth print-access-token)"

```

* Verify it is deployed.
```
$ kubectl cloudflow list

NAME              NAMESPACE         VERSION           CREATION-TIME     
spark-sensors     spark-sensors     34-69082eb-dirty  2019-11-08 13:41:18 +0000 UTC
```

7) Check all pods are running.

```
$ kubectl get pods -n spark-sensors
NAME                                         READY   STATUS              RESTARTS   AGE
spark-sensors-egress-1573220481661-exec-1    1/1     Running             0          2m10s
spark-sensors-egress-1573220481661-exec-2    0/1     ContainerCreating   0          2m9s
spark-sensors-egress-driver                  1/1     Running             0          2m26s
spark-sensors-ingress-1573220481674-exec-1   1/1     Running             0          2m10s
spark-sensors-ingress-1573220481674-exec-2   1/1     Running             0          2m10s
spark-sensors-ingress-driver                 1/1     Running             0          2m26s
spark-sensors-process-1573220481718-exec-1   1/1     Running             0          2m11s
spark-sensors-process-1573220481718-exec-2   1/1     Running             0          2m11s
spark-sensors-process-driver     
```

* Verify the application output.

```
$ kubectl log spark-sensors-egress-driver -n spark-sensors

Loading application.conf from: /etc/cloudflow-runner/application.conf, secret config from: /etc/cloudflow-runner-secret/secret.conf
-------------------------------------------
Batch: 0
-------------------------------------------
+------+-----+------------------+
|   src|gauge|             value|
+------+-----+------------------+
|src-33|  gas| 542.1937869223677|
|src-97|  oil|  199.447772002829|
|src-97|  oil|126.10546562207755|
|src-33|  gas|14.283019037600049|
|src-33|  gas| 157.4400550304717|
|src-97|  gas|180.00588704073294|
|src-33|  oil| 2.546641892374604|
|src-97|  oil| 2.504638815095256|
|src-33|  gas| 542.1937869223677|
|src-97|  oil| 47.51830204639738|
|src-33|  oil|488.06696803076454|
|src-33|  gas| 85.35098547704779|
|src-97|  oil| 54.88743931232827|
|src-97|  oil|126.10546562207755|
|src-33|  gas| 85.35098547704779|
|src-33|  oil|488.06696803076454|
|src-97|  oil| 47.51830204639738|
|src-97|  oil| 2.504638815095256|
|src-33|  oil| 2.546641892374604|
|src-33|  gas| 157.4400550304717|
+------+-----+------------------+
only showing top 20 rows

-------------------------------------------
Batch: 1
-------------------------------------------
+------+-----+------------------+
|   src|gauge|             value|
+------+-----+------------------+
|src-97|  oil| 11.28699131177144|
|src-33|  gas| 54.13269242596257|
|src-33|  gas| 988.3230454723247|
|src-97|  gas| 148.3055826758501|
|src-97|  gas| 148.3055826758501|
|src-33|  gas| 54.13269242596257|
|src-97|  oil|  760.024470736732|
|src-97|  oil|  760.024470736732|
|src-33|  gas| 988.3230454723247|
|src-97|  gas|1042.3404196321565|
|src-97|  gas|1042.3404196321565|
|src-33|  oil| 27.75132596682821|
|src-33|  gas| 976.6856203943967|
|src-97|  gas| 385.3532592940638|
|src-33|  gas| 976.6856203943967|
|src-33|  oil| 27.75132596682821|
|src-97|  gas| 385.3532592940638|
|src-33|  gas|1269.5736118755415|
|src-97|  oil|188.75469481408513|
|src-33|  gas|1269.5736118755415|
+------+-----+------------------+
only showing top 20 rows

-------------------------------------------
Batch: 2
-------------------------------------------
+------+-----+------------------+
|   src|gauge|             value|
+------+-----+------------------+
|src-33|  oil| 847.6950249637855|
|src-33|  gas| 894.1615286881954|
|src-33|  oil| 847.6950249637855|
|src-97|  gas| 901.3472093816678|
|src-97|  gas| 1143.234750548319|
|src-97|  gas| 901.3472093816678|
|src-97|  gas| 1143.234750548319|
|src-72|  oil|1571.1498223984206|
|src-21|  oil|148.37603003222227|
|src-72|  oil|1571.1498223984206|
|src-21|  gas|453.74071025583896|
|src-21|  gas|453.74071025583896|
|src-21|  oil|148.37603003222227|
|src-10|  gas|1649.6090844251842|
|src-10|  gas| 178.6005146200035|
|src-92|  oil| 376.5333753643078|
|src-92|  oil| 376.5333753643078|
|src-10|  gas|1649.6090844251842|
|src-10|  gas| 178.6005146200035|
|src-19|  gas|1547.4380898089883|
+------+-----+------------------+
only showing top 20 rows
```

* Undeploy.

```
$ kubectl cloudflow undeploy spark-sensors
```

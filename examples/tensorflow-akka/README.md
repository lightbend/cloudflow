## `tensorflow-akka`

### Problem Definition

A simple pipeline that scores the quality of wines using a TensorFlow model.

The TensorFlow models must be provided through a persistent volume claim. 
A `load-models` script is provided to load TensorFlow SavedModelBundles to the volume that is mounted on the model-server streamlet.

The model-server can be configured to use any of the loaded models, by choosing a different SavedModelBundle directory.
(This directory has to be copied first to the volume, before the application is deployed or re-configured.) 


### Required configuration

`model-server.models`
Persistent volume claim for the directory that contains TensorFlow SavedModelBundle directories.

`model-server.model`
The relative directory name under /models that must be used to load the TensorFlow SavedModelBundle from. 

### Generating data
The WineRecordGenerator Streamlet generates wine records.

##### Run
TODO

### Example Deployment on GKE

* Make sure you have created a GKE cluster and installed Cloudflow as per the [installation guide](https://github.com/lightbend/cloudflow-installer).
Verify access to your cluster:

```
$ gcloud container clusters get-credentials <CLUSTER_NAME>
```

and that you have access to the Google docker registry:

```
$ gcloud auth configure-docker
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
...
[info] You can deploy the application to a Kubernetes cluster using any of the the following commands:
[info]  
[info]   kubectl cloudflow deploy eu.gcr.io/<projectID>/tensorflow-akka:8-2a0f65d-dirty
[info]  
[success] Total time: 19 s, completed Nov 25, 2019 11:22:53 AM

```

* Make sure you have the `kubectl cloudflow` plugin configured.

```
$ kubectl cloudflow help
This command line tool can be used to deploy and operate Cloudflow applications.
...
```

* Install the required PVC for the models.

First create the application namespace (since a PVC is namespaced).

```
$ kubectl create ns tensorflow-akka
```

claim.yaml:

```
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: claim1
  namespace: tensorflow-akka
spec:
  accessModes:
    - ReadWriteMany
  storageClassName: nfs-client
  resources:
    requests:
      storage: 1Gi
```

```
$ kubectl apply -f claim.yaml
```

Copy the models to the persistent volume:

```
$ ./load-models.sh
```
* Deploy the app.

TODO update to use passwd-stdin

```
$ kubectl cloudflow  deploy -u oauth2accesstoken --volume-mount model-server.wine-models=claim1  eu.gcr.io/<projectID>/tensorflow-akka:<version> -p "$(gcloud auth print-access-token)"
```

* Verify that the application is deployed.

```
$ kubectl cloudflow list

NAME              NAMESPACE         VERSION           CREATION-TIME     
tensorflow-akka   tensorflow-akka   8-2a0f65d-dirty   2019-11-25 13:22:44 +0200 EET
```

* Check all pods are running.

```
$ kubectl get pods -n tensorflow-akka
NAME                                                  READY   STATUS    RESTARTS   AGE

TODO

```

* Verify the application output

* Undeploy.

```
$ kubectl cloudflow undeploy tensorflow-akka
```

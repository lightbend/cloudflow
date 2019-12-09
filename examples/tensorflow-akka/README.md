## `tensorflow-akka`

### Problem Definition

A simple pipeline that scores the quality of wines using a TensorFlow model.

The TensorFlow models must be provided through a persistent volume claim. 
The `models` directory contains a `copy-models.sh` script that copies the models found in this directory to the volume that is mounted on the model-server streamlet.
(The example only contains one example model, under `models/model-1/saved/1`)

The model-server can be configured to use any of the loaded models, by choosing a different `SavedModelBundle` directory.
(This directory has to be copied first to the volume, before the application is deployed with `kubectl cloudflow deploy` or re-configured with `kubectl cloudflow configure`.) 

### Required configuration

`model-server.models`
Persistent volume claim for the directory that contains TensorFlow SavedModelBundle directories.

`model-server.model`
The relative directory name under /models that must be used to load the TensorFlow SavedModelBundle from. 

You can first deploy the app with `kubectl cloudflow deploy`, providing a model that you want to use.
When you want to change the model, add a model to the `models` directory, run the `copy-models.sh` script in the `models` directory, and choose the path to the new model, 
for instance, to load a (hypothetical) model under `model-2/saved/1`:

```
$ kubectl cloudflow configure tensorflow-akka model-server.model=model-2/saved/1
```

### Generating data

The WineRecordGenerator Streamlet generates wine records. Creating a streamlet to receive wine records via HTTP for instance, is left as an exercise for the reader.

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

Copy the models in this project to the persistent volume:

```
$ cd models
$ ./copy-models.sh eu.gcr.io/<projectID>
```
* Deploy the app.

```
$ kubectl cloudflow  deploy -u oauth2accesstoken --volume-mount model-server.wine-models=claim1 model-server.model=model-1/saved/1 eu.gcr.io/<projectID>/tensorflow-akka:<version> -p "$(gcloud auth print-access-token)"
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

NAME                                                    READY   STATUS      RESTARTS   AGE
copy-models-wlsk5                                       0/1     Completed   0          76s
tensorflow-akka-console-egress-6d9c8b6c44-h5t8p         1/1     Running     1          4h40m
tensorflow-akka-model-server-7777c6c69f-klph4           1/1     Running     3          4h31m
tensorflow-akka-wine-record-generator-fd79bd5f4-ldrwg   1/1     Running     0          4h40m
```

* Verify the application output

```
$ kubectl -n tensorflow-akka logs tensorflow-akka-console-egress-6d9c8b6c44-h5t8p

{"inputRecord": {"datatype": "wine", "fixed_acidity": 6.8, "volatile_acidity": 0.77, "citric_acid": 0.0, "residual_sugar": 1.8, "chlorides": 0.066, "free_sulfur_dioxide": 34.0, "total_sulfur_dioxide": 52.0, "density": 0.9976, "pH": 3.62, "sulphates": 0.68, "alcohol": 9.9}, "modelResult": {"value": 5.0}, "modelResultMetadata": {"errors": "", "modelName": "model-1/saved/1", "startTime": 1575921833212, "duration": 0}}
```

* Undeploy.

```
$ kubectl cloudflow undeploy tensorflow-akka
```

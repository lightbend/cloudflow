# Cloudflow installer

This is the installer for the [Cloudflow](https://github.com/lightbend/cloudflow) toolkit. 

This installer deploys all the backend components required to turn your Kubernetes cluster into a Cloudflow-compliant platform.

The Cloudflow installer deploys:
- The Cloudflow operator, which orchestrates the deployment of Cloudflow applications
- The [Spark Operator](https://github.com/GoogleCloudPlatform/spark-on-k8s-operator)
- The [Strimzi Kafka Operator](https://strimzi.io/) used to manage Apache Kafka clusters co-located or pre-existing
- The required service accounts with the minimal permissions needed by the supporting components

Additionally, this installer deploys:
- NFS - a supporting component that provides a shareable file system to enable storage for stateful applications

Currently, the provided installation scripts are validated for Google Kubernetes Engine (GKE) on the Google Cloud Platform. 
Testing on other major cloud providers is in the roadmap.

## Installation Procedure

To install Cloudflow on GKE it is a straightforward process:

```
# create a GKE cluster with name <gke-cluster-name>
$ ./create-cluster-gke.sh <gke-cluster-name>
# install Cloudflow in that cluster
$ ./install-gke.sh <gke-cluster-name>
```
Replace above `<gke-cluster-name>` with the preferred name for
your GKE cluster.

Notes
-----
- `create-cluster-gke.sh` is optional. 
It creates a cluster on GKE that's large enough to launch several applications.
You can also opt to create a cluster customized to your needs by either changing the values in the `create-cluster-gke.sh`, using the [Google Cloud Console](cloud.google.com), or the `gcloud` CLI.

- The `lightbend` namespace
The installer creates a namespace called `lightbend` where all supporting components are installed.
This restriction will be removed in the future.

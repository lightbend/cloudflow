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

## Prerequisites

### K8s CLI's
You need the command line tool for your particular Kubernetes cluster:
* [Google Cloud SDK](https://cloud.google.com/sdk/)
* [Amazon CLI for EKS](https://eksctl.io/)

### Utilities
* [jq](https://stedolan.github.io/jq/)
* [Helm](https://helm.sh/) *note: Cloudflow installer is currently compatible with both v2 and v3*

## Installation Procedure

### GKE
To install Cloudflow on GKE it is a straightforward process:

```
# create a GKE cluster with name <cluster-name>
$ ./create-cluster-gke.sh <cluster-name>
# install Cloudflow in the GKE cluster
$ ./install.sh gke
```
Replace above `<cluster-name>` with the preferred name for your GKE cluster.

### EKS
Similarly, to install Cloudflow on EKS follow this process:

```
# create an EKS cluster with name <cluster-name>
$ ./create-cluster-eks.sh <cluster-name> <aws-region>
# install Cloudflow in the EKS cluster
$ ./install.sh eks
```
Replace above `<cluster-name>` with the preferred name for your EKS cluster.

## Uninstall Procedure

In case of a failed installation, or if you simply want to uninstall entirely, run `./uninstall-cloudflow.sh`.

Notes
-----
- `create-cluster-<gke|eks>.sh` is optional. 
It creates a cluster on GKE/EKS that's large enough to launch several applications.
You can also opt to create a cluster customized to your needs by either changing the values in the 
`create-cluster-gke.sh`, using the [Google Cloud Console](cloud.google.com), or the `gcloud` CLI for GKE.
Similarly, you can also opt to create a cluster customized to your needs by either changing the values in the
`create-cluster-eks.sh`, using the [Amazon Web Services Console](aws.amazon.com), or the `ekstl` CLI for EKS.

- The `cloudflow` namespace
The installer creates a namespace called `cloudflow` where all supporting components are installed.
This restriction will be removed in the future.

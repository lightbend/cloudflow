# Starting a Kubernetes Cluster

This document covers how to launch a Kubernetes cluster in a cloud environment.

## Prerequisites

### CLI's
You need the command line tool for the cloud environment of your choosing:
* [Google Cloud SDK](https://cloud.google.com/sdk/)
* [Amazon CLI for EKS](https://eksctl.io/) (Note: Make sure you have the latest `aws-cli` version.)
* [Azure CLI](https://docs.microsoft.com/en-us/cli/azure/install-azure-cli?view=azure-cli-latest)

### Utilities
* [jq](https://stedolan.github.io/jq/)
* [Helm](https://helm.sh/) *note: Cloudflow installer is currently compatible with both v2 and v3*

## Starting a cluster

### Google Kubernetes Engine (GKE)
```bash
# create a GKE cluster with name <cluster-name>
$ ./create-cluster-gke.sh <cluster-name>
```
Replace above `<cluster-name>` with the preferred name for your GKE cluster.

### Amazon Elastic Kubernetes Service (EKS)
Similarly, to launch an EKS cluster:

```bash
# create an EKS cluster with name <cluster-name>
$ ./create-cluster-eks.sh <cluster-name> <aws-region>
```
Replace above `<cluster-name>` with the preferred name for your EKS cluster.

### Azure Kubernetes Service (AKS)

To launch an AKS cluster:

```bash
$ ./create-cluster-aks.sh -c <cluster-name> --resource-group <group-name>
```

#### EFS integration with EKS

Some extra considerations are needed when integrating EFS with EKS. Please make sure the user launching the cluster satisfies the security groups [requirements](https://docs.aws.amazon.com/efs/latest/ug/accessing-fs-create-security-groups.html).

Notes
-----
- `create-cluster-<gke|eks|aks>.sh` is optional.
  It creates a cluster on GKE/EKS/AKS that's large enough to launch several Cloudflow applications.
  You can also opt to create a cluster customized to your needs by either changing the values in the
  `create-cluster-gke.sh`, using the [Google Cloud Console](cloud.google.com), or the `gcloud` CLI for GKE.
  Similarly, you can also opt to create a cluster customized to your needs by either changing the values in `create-cluster-eks.sh`, using the [Amazon Web Services Console](aws.amazon.com), or the `ekstl` CLI for EKS.

  Same is true for AKS, you are free to create a cluster customized to your needs by either changing the values in `create-cluster-aks.sh`, using the [Azure Cloud Shell]([https://shell.azure.com](https://shell.azure.com/)), or the Azure CLI.

- The `cloudflow` namespace
The installer creates a namespace called `cloudflow` where all supporting components are installed. This restriction will be removed in the future.

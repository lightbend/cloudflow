# Cloudflow-installer

This is the installer for the [Cloudflow](https://github.com/lightbend/cloudflow) toolkit. Currently we support GKE and we are planning to support other environments in the future.

To install Cloudflow on GKE it is a straightforward process:

```
# create a GKE cluster with name <gke-cluster-name>
$ ./gke-create-cluster.sh <gke-cluster-name>
# install Cloudflow in that cluster
$ ./install-gke.sh <gke-cluster-name>
```
Replace above `<gke-cluster-name>` with the preferred name for
your GKE cluster.

Note: The installer creates all deployments under `lightbend` namespace in the GKE cluster but this restriction will be removed
in the future.

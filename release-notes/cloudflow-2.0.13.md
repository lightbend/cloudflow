# Cloudflow 2.0.13 Release Notes (November 9th, 2020)

Today we are happy to announce the availability of Cloudflow 2.0.13. 

This patch release fixes a number of issues that have been reported since 2.0.12.

From version 2.0.13, Flink and Spark applications require a Persistent Volume Claim (PVC) to exist before the application is deployed.
This change allows users to manage and own their state storage. As of version 2.0.13, Cloudflow does not create or delete PVCs anymore.

It also means that you do not have to specify a `storageClass` when installing Cloudflow.

If you have existing applications that run on previously auto-created PVCs, it is best to move to PVCs that you can manage yourself:
- Create a PVC named `cloudflow-flink` for Flink streamlets and `cloudflow-spark` for Spark streamlets in the namespace of the application and re-deploy the application.
- It is also possible to configure a PVC mount in a configuration file, as long as it mounts a `/mnt/spark/storage` or `/mnt/flink/storage` path respectively for Spark or Flink, which will be used if Cloudflow cannot find a `cloudflow-flink` or `cloudflow-spark` PVC.

For existing users of previous 2.0.x versions of Cloudflow it is important to note that the Cloudflow operator must be upgraded with the helm charts, as described in the documentation. The Cloudflow kubectl plugin must be upgraded to 2.0.13 (links below). 
Existing applications built with versions before 2.0.12 must be rebuilt with the 2.0.13 sbt-cloudflow plugin.

Details of the fixes in this release can be found [here](https://github.com/lightbend/cloudflow/releases/tag/v2.0.13). 

**The Cloudflow 2.0.13 `kubectl` plugin can be downloaded using one of the following links:**

* [Linux](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.13.841-40cc6eb-linux-amd64.tar.gz)
* [MacOS](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.13.841-40cc6eb-darwin-amd64.tar.gz)
* [Windows](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.13.841-40cc6eb-windows-amd64.tar.gz)

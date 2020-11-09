# Cloudflow 2.0.13 Release Notes (November 9th, 2020)

Today we are happy to announce the availability of Cloudflow 2.0.13. 

This patch release fixes a number of issues that have been reported since 2.0.12.

From version 2.0.13, Flink and Spark applications require a Persistent Volume Claim (PVC) to already exist, which will be used to store state.
This change allows users to manage that data. It is safe to undeploy an application, Cloudflow will not delete the PVC since it did not create it.
It also means that you do not have to specify a `storageClass` when installing Cloudflow.
If you have existing applications that run on auto-created PVCs and you want to move to your own PVCs:
- create a PVC named `cloudflow-flink` for Flink streamlets and `cloudflow-spark` for Spark streamlets in the namespace of the application and re-deploy the application.
- it is also possible to configure a PVC mount in a configuration file, as long as it mounts a `/mnt/spark/storage` or `/mnt/flink/storage` path respectively for Spark or Flink, which will be used if Cloudflow cannot find a `cloudflow-flink` or `cloudflow-spark` PVC.

It is now also possible to configure `taskmanager.taskSlots`, `parallelism.default` and `jobmanager.replicas` in a Flink config section, please see https://cloudflow.io/docs/current/develop/cloudflow-configuration.html#_configuring_a_runtime_using_the_runtime_scope.

For existing users of previous 2.0.x versions of Cloudflow 2.0.10 it is important to note that Cloudflow must be upgraded with the helm charts, as described in the documentation. The Cloudflow kubectl plugin must also be upgraded to 2.0.13 (links below) and existing applications must be rebuilt with the 2.0.13 sbt-cloudflow plugin. Stricter verification has been put in place that all of these have been upgraded to 2.0.13 to prevent incompatibility issues.

We have moved from Gitter to [Zulip](https://cloudflow.zulipchat.com/), please sign up and continue to chat with us there. 

Details of the fixes in this release can be found [here](https://github.com/lightbend/cloudflow/releases/tag/v2.0.13). 

**The Cloudflow 2.0.13 `kubectl` plugin can be downloaded using one of the following links:**

* [Linux](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-TODO-linux-amd64.tar.gz)
* [MacOS](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-TODO-darwin-amd64.tar.gz)
* [Windows](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-TODO-windows-amd64.tar.gz)

# Cloudflow 2.0.10 Release Notes (September 8th, 2020)

Today we are happy to announce the availability of Cloudflow 2.0.10. 
Most notably, Cloudflow 2.0.10 can now be installed with helm charts. See the Cloudflow helm charts [here](https://github.com/lightbend/cloudflow-helm-charts). See [the installation documentation](https://cloudflow.io/docs/current/administration/index.html) for more details.
Helm charts are far easier to customize, support upgrades out of the box, and provide a more modular approach to installing Cloudflow and the operators it integrates with. 

Cloudflow 2.0.10 does not depend on the Strimzi Entity and Cluster operators anymore. It's now possible to let Cloudflow manage topics on any kafka cluster.

You can optionally install Flink and Spark operators, if you respectively use Flink and Spark Streamlets. This makes Cloudflow substantially more lightweight for applications that do not use Flink or Spark.

Other notable changes in Cloudflow 2.0.10:
- Fix for logging bug caused by adding log4j jar files to the classpath
- Akka gRPC support
- The configuration format now supports adding custom labels to pods.
- cloudflow.runtimes.flink.config support for runLocal
- Documentation improvements

**The Cloudflow 2.0.10 `kubectl` plugin can be downloaded using one of the following links:**

* [Linux](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.10.717-6cf9b406-linux-amd64.tar.gz)
* [MacOS](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.10.717-6cf9b406-darwin-amd64.tar.gz)
* [Windows](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.10.717-6cf9b406-windows-amd64.tar.gz)

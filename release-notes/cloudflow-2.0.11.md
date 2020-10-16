# Cloudflow 2.0.11 Release Notes (October 15th, 2020)

Today we are happy to announce the availability of Cloudflow 2.0.11. 

Cloudflow 2.0.11 is installed and upgraded with helm charts. See the Cloudflow helm charts [here](https://github.com/lightbend/cloudflow-helm-charts). See [the installation documentation](https://cloudflow.io/docs/current/administration/index.html) for more details.
A new setting `kafkaClusters.default.bootstrapServers` is required for 2.0.11 so be sure to set this to your existing kafka cluster in the helm upgrade command, for instance by adding `--set kafkaClusters.default.bootstrapServers=cloudflow-strimzi-kafka-bootstrap.cloudflow:9092` to the helm upgrade command shown in the documentation.

For existing users of Cloudflow 2.0.10 it is important to note that Cloudflow must be upgraded with the helm charts, as described in the documentation. The Cloudflow kubectl plugin must also be upgraded to 2.0.11 (links below) and existing applications must be rebuilt with the 2.0.11 sbt-cloudflow plugin.

New features in this release:
- Named Kafka cluster configurations. The helm chart makes it possible to define the default Kafka cluster and any other Kafka clusters that applications might use. The cluster names can then be used in blueprints. Because of this change it is now also possible to set defaults for many Kafka settings, and it is possible to connect to the named Kafka clusters with TLS settings.
- Mount existing persistent volume claims through configuration files.
- Mounting existing secrets through configuration files.
- Protobuf support for Spark.
- Java DSL for protobuf inlet/outlet.
- runLocalLog4jConfigFile setting for custom log4j configuration used in runLocal.
- Added methods to change Spark session and Flink streaming env in streamlet code.
- In `sbt buildApp`, publishing images to a Docker Registry is now optional, making it possible to build the Cloudflow application CR, and push the images independently.
- `sbt printAppGraph` prints the ascii visualization as a standalone task.

This release also fixes many issues, more details can be found [here](https://github.com/lightbend/cloudflow/releases/tag/v2.0.11). 

**The Cloudflow 2.0.11 `kubectl` plugin can be downloaded using one of the following links:**

* [Linux](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.11.785-51544d5-linux-amd64.tar.gz)
* [MacOS](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.11.785-51544d5-darwin-amd64.tar.gz)
* [Windows](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.11.785-51544d5-windows-amd64.tar.gz)

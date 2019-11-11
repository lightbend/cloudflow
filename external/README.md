### Cloudflow Artifacts And Tooling

The current published artifacts are:

* [Spark distribution file](https://github.com/lightbend/spark/releases/download/cloudflow-2.4.4-k8s-client-upgrade/spark-2.4.4-bin-cloudflow-2.12.tgz).
* `lightbend/sparkoperator:1.3.0-M1-OpenJDK-2.4.4-0.8.2-cloudflow-2.12`
* `lightbend/spark:1.3.0-M1-OpenJDK-2.4.4-cloudflow-2.12`
* `lightbend/cloudflow-base:1.3.0-M1-spark-2.4.4-flink-1.9.1-scala-2.12`
* Cloudflow jars in: https://lightbend.bintray.com/cloudflow.
* Cloudflow cli in: https://bintray.com/lightbend/cloudflow-cli.
* CLI Download Links:
  * [MacOS](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-1.3.0-M1.37-1e4b7f3-darwin-amd64.tar.gz)
  * [Linux (amd64 arch)](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-1.3.0-M1.37-1e4b7f3-linux-amd64.tar.gz)
  * [Win (64 bits)] (https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-1.3.0-M1.37-1e4b7f3-windows-amd64.tar.gz)

#### Cloudflow Runtime Image

This is the base image used by Cloudflow user applications.
For more check [here](base-image/README.md).

#### Spark Distribution And Spark Images

We build our own Spark distribution because we are targeting by default Scala 2.12 which is not the default language for Spark v2.4.x.
The [releaseSparkDist.sh]( external/spark/releaseSparkDist.sh) script creates the appropriate Spark distribution file and pushes it
to `https://github.com/lightbend/spark` by creating a new release. It also creates the appropriate Spark image to be used by the
`Cloudflow Runtime Image` and the Spark Operator image to be used by the Cloudflow installer.
The script uses [hub](https://hub.github.com/) to make the github release. For automating the release you need to set `GITHUB_TOKEN`, for more check the related [documentation](https://hub.github.com/hub.1.html).

#### Flink Distribution And Flink Images

Cloudflow uses the latest official Flink distribution in order to add Flink support in the `Cloudflow Runtime Image`.
It also uses the latest stable image from the [Lyft Flink operator](https://github.com/lyft/flinkk8soperator)
to deploy the Flink Operator via its installer.

#### Strimzi

Cloudflow uses the official upstream Strimzi releases and the related publicly available artifacts.

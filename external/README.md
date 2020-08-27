### Cloudflow Artifacts And Tooling

The current published artifacts are:

* [Spark distribution file](https://github.com/lightbend/spark/releases/download/2.4.5-lightbend/spark-2.4.5-bin-cloudflow-2.12.tgz).
* `lightbend/sparkoperator:2.0.9-cloudflow-spark-2.4.5-1.1.2-scala-2.12`
* `lightbend/spark:2.0.9-OpenJDK-2.4.5-cloudflow-2.12`
* `lightbend/flink:2.0.9-cloudflow-flink-1.10.0-scala-2.12`
* `lightbend/akka-base:2.0.9-cloudflow-akka-2.6.6-scala-2.12`
* Cloudflow jars: https://lightbend.bintray.com/cloudflow.
* Cloudflow `kubectl` plugin: https://bintray.com/lightbend/cloudflow-cli.

#### Spark

We build our own Spark distribution because we are targeting by default Scala 2.12 which is not the default language for Spark v2.4.x.

The [buildLightbendSpark.sh](multi-base-images/spark/buildLightbendSpark.sh) script:

1. Creates an appropriate Spark distribution. 
2. Builds and pushes a Spark image to be used as a Cloudflow application base image
3. Builds and pushes a Spark Operator image to be used by the Cloudflow installer.

#### Strimzi

Cloudflow uses the official Strimzi releases and the related publicly available artifacts.


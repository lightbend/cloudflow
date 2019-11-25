# Getting Started

This document introduces the basic steps needed to install all components of Cloudflow and deploy and run a simple Cloudflow application on the GKE cluster.

We will discuss the following steps in sequence:

1. Download and install Cloudflow CLI
2. Go through all steps of developing a sample "hello world" application using Cloudflow libraries
3. Install a GKE cluster following instructions in `cloudflow-installer` repository
4. Build the application and publish to GKE repository using `sbt buildAndPublish`
5. Deploy the application to the cluster using Cloudflow CLI
6. Show usage of CLI helpers like `status`, `list` etc.
7. Show how to deal with http ingress and push some data to the application
8. Verify that the application works
9. Undeploy the application


## Download and install the CLI

Cloudflow CLI can be downloaded from [lightbend public bintray repository](https://bintray.com/lightbend/cloudflow-cli). Download the version appropriate for your platform and install it in your local system. Please make sure you have the executable in your path with proper permission settings.

## Develop a sample Cloudflow application

In this section, we will develop a sample application that will feature the main components of Cloudflow. It will be a simple "hello world" style application, expressive enough to demonstrate the major features of Cloudflow. It will not have many of the advanced features that Cloudflow offers - the main idea is to give you a feel for what it takes to build a complete application and deploy it in running condition in a GKE cluster.

We will develop a simple pipeline that processes events from a wind turbine farm. The application will receive streaming data that will pass through the following transformations :

* ingested by a streamlet (ingress)
* processed by a streamlet to convert into a domain object (processor)
* validated by a streamlet (splitter) that separates valid and invalid records
* logged in to loggers to be checked at output (egress)

Each of the above terminologies (ingress, processor, splitter and egress) are explained in the [Basic Concepts](Basic%20Concepts.md). Here's an overview of the application pipeline architecture:

![Application Pipeline](images/pipe.001.jpeg?raw=true "Application Pipeline")

One of the important features of Cloudflow architecture is the complete separation of the components from how they are connected as part of the pipeline. The _streamlets_ described above are the individual building blocks of the pipeline. You can connect them using a declarative language that forms the _blueprint_ of the pipeline. Streamlets can be shared across blueprints making them individual reusable objects. And just like streamlets, blueprints also form an independent component of a Cloudflow application.

### Project structure

Here's how we would structure a typical Cloudflow application as a project.

```
   |-project
   |-src
   |---main
   |-----avro
   |-----blueprint
   |-----resources
   |-----scala
   |-------sensordata
   |-build.sbt
```

This is a Scala project and we have the following structural components at the leaf level of the above tree:

* **avro** : contains the avro schema of the domain objects
* **blueprint** : contains the blueprint of the application in a file named `blueprint.conf`
* **scala** : contains the source code of the application under the package name `sensordata`
* **build.sbt** : the sbt build script

### The sbt build script

Here's a minimal version of the sbt build script:

**build.sbt:**

```
import sbt._
import sbt.Keys._

lazy val sensorData =  (project in file("."))
  .enablePlugins(CloudflowAkkaStreamsApplicationPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "com.lightbend.akka"     %% "akka-stream-alpakka-file"  % "1.1.2",
      "com.typesafe.akka"      %% "akka-http-spray-json"      % "10.1.10",
      "ch.qos.logback"         %  "logback-classic"           % "1.2.3",
      "com.typesafe.akka"      %% "akka-http-testkit"         % "10.1.10" % "test"
    ),
    name := "sensor-data",
    organization := "com.lightbend",

    scalaVersion := "2.12.10",
    crossScalaVersions := Vector(scalaVersion.value)
  )
```

Cloudflow offers several sbt plugins that abstract quite a bit of boilerplates necessary to build a complete application. In this example we use the plugin `CloudflowAkkaStreamsApplicationPlugin` that provides you all building blocks of developing an Akka Streams based Cloudflow application.

> **Note:** You can use multiple plugins to develop an application that uses multiple runtimes (Akka, Spark, Flink etc.). For simplicity of this example we will be using only one.

The above build script is standard Scala sbt - the only difference is the plugin which we provide as part of Cloudflow.

### Schema first approach

In Cloudflow, streamlets work with optional inputs and outputs that are statically typed. The types represent objects that the specific input / output can handle. The first step in application development is to encode the objects in the form of [avro schema](https://avro.apache.org/docs/current/). Cloudflow will generate appropriate classes corresponding to each schema.

Let's start building the avro schema for the domain objects that we need for the application. These schema files will have an extension `.avsc` and will go directly under `src/main/avro` in the project structure that we discussed earlier.

**SensorData :** The data that we receive from the source and ingested through our ingress (`SensorData.avsc`).

```
{
    "namespace": "sensordata",
    "type": "record",
    "name": "SensorData",
    "fields":[
         {
            "name": "deviceId",
            "type": {
                "type": "string",
                "logicalType": "uuid"
            }
         },
         {
            "name": "timestamp",
            "type": {
                "type": "long",
                "logicalType": "timestamp-millis"
            }
         },
         {
            "name": "measurements", "type": "sensordata.Measurements"
         }
    ]
}

```

**Measurements :** A substructure of `SensorData` (`Measurements.avsc`)

```
{
    "namespace": "sensordata",
    "type": "record",
    "name": "Measurements",
    "fields":[
         {
            "name": "power", "type": "double"
         },
         {
            "name": "rotorSpeed", "type": "double"
         },
         {
            "name": "windSpeed", "type": "double"
         }
    ]
}
```

**Metric :** A domain object that we would like to track and measure (`Metric.avsc`)

```
{
    "namespace": "sensordata",
    "type": "record",
    "name": "Metric",
    "fields":[
         {
            "name": "deviceId",
            "type": {
                "type": "string",
                "logicalType": "uuid"
            }
         },
         {
            "name": "timestamp",
            "type": {
                "type": "long",
                "logicalType": "timestamp-millis"
            }
         },
         {
            "name": "name", "type": "string"
         },
         {
            "name": "value", "type": "double"
         }
    ]
}
```

**InvalidMetric :** An object that abstracts the erroneous metric (`InvalidMetric.avsc`)

```
{
    "namespace": "sensordata",
    "type": "record",
    "name": "InvalidMetric",
    "fields":[
         {
            "name": "metric", "type": "sensordata.Metric"
         },
         {
            "name": "error", "type": "string"
         }
    ]
}
```

> **Note:** The above schema files are processed during the build process through the infrastructure of the Cloudflow plugin system. For each of these schema files, Cloudflow will generate Scala case classes that can be directly used from within the application.


### Let's build some streamlets

All streamlets will reside under `src/main/scala/sensordata` where `sensordata` is the package name. Let's start with the ingress, which we implement in a class named `SensorDataHttpIngress`.

**SensorDataHttpIngress.scala**

```
package sensordata

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import cloudflow.akkastream._
import cloudflow.akkastream.util.scaladsl._

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import SensorDataJsonSupport._

class SensorDataHttpIngress extends AkkaServerStreamlet {
  val out = AvroOutlet[SensorData]("out").withPartitioner(RoundRobinPartitioner)
  def shape = StreamletShape.withOutlets(out)
  override def createLogic = HttpServerLogic.default(this, out)
}
```

The above ingress has an outlet through which ingested data is passed downstream through the pipeline. In any streamlet class the `StreamletLogic` abstracts the behavior and we use the default behavior that `HttpServerLogic` offers out of the box.

Ingested data is then passed to another streamlet `metrics` which converts objects of type `SensorData` to a domain object `Metric`.

**SensorDataToMetrics.scala**

```
package sensordata

import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets.{ RoundRobinPartitioner, StreamletShape }
import cloudflow.streamlets.avro._

class SensorDataToMetrics extends AkkaStreamlet {
  val in = AvroInlet[SensorData]("in")
  val out = AvroOutlet[Metric]("out").withPartitioner(RoundRobinPartitioner)
  val shape = StreamletShape(in, out)
  def flow = {
    FlowWithOffsetContext[SensorData]
      .mapConcat { data ⇒
        List(
          Metric(data.deviceId, data.timestamp, "power", data.measurements.power),
          Metric(data.deviceId, data.timestamp, "rotorSpeed", data.measurements.rotorSpeed),
          Metric(data.deviceId, data.timestamp, "windSpeed", data.measurements.windSpeed)
        )
      }
  }
  override def createLogic = new RunnableGraphStreamletLogic() {
    def runnableGraph = sourceWithOffsetContext(in).via(flow).to(sinkWithOffsetContext(out))
  }
}
```

The above streamlet has an inlet and an outlet and processes data that it receives using the business logic. In this example we convert `SensorData` to `Metric` - note that the inlets and outlets are typed accordingly.

Now that we have the metric that we would like to measure and track, we need to validate them as per business rules. We have a separate streamlet (`validation`) for doing exactly this.

**MetricsValidation.scala**

```
package sensordata

import cloudflow.akkastream._
import cloudflow.akkastream.util.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._

class MetricsValidation extends AkkaStreamlet {
  val in = AvroInlet[Metric]("in")
  val invalid = AvroOutlet[InvalidMetric]("invalid").withPartitioner(metric ⇒ metric.metric.deviceId.toString)
  val valid = AvroOutlet[Metric]("valid").withPartitioner(RoundRobinPartitioner)
  val shape = StreamletShape(in).withOutlets(invalid, valid)

  override def createLogic = new SplitterLogic(in, invalid, valid) {
    def flow = flowWithOffsetContext()
      .map { metric ⇒
        if (!SensorDataUtils.isValidMetric(metric)) Left(InvalidMetric(metric, "All measurements must be positive numbers!"))
        else Right(metric)
      }
  }
}
```

The above streamlet has an inlet and 2 outlets for generating valid and invalid metrics. And all of them are typed based on the data that they are expected to handle. In the behavior handled by `createLogic` method, `SensorDataUtils.isValidMetric(..)` handles the business validation. We implement that logic in the next class.

**SensorDataUtils.scala**


```
package sensordata

object SensorDataUtils {
  def isValidMetric(m: Metric) = m.value >= 0.0
}
```

In real life, we will have more complex business logic - this is just for demonstration.

Finally, we are down to the point where we can log the valid and invalid metrics separately in 2 streamlets - `valid-logger` and `invalid-logger`.


**ValidMetricLogger.scala**

```
package sensordata

import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._

class ValidMetricLogger extends AkkaStreamlet {
  val inlet = AvroInlet[Metric]("in")
  val shape = StreamletShape.withInlets(inlet)

  override def createLogic = new RunnableGraphStreamletLogic() {

    def log(metric: Metric) = {
      system.log.info(metric.toString)
    }

    def flow = {
      FlowWithOffsetContext[Metric]
        .map { validMetric ⇒
          log(validMetric)
          validMetric
        }
    }

    def runnableGraph = {
      sourceWithOffsetContext(inlet).via(flow).to(sinkWithOffsetContext)
    }
  }
}
```

**InvalidMetricLogger.scala**

```
package sensordata

import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._

class InvalidMetricLogger extends AkkaStreamlet {
  val inlet = AvroInlet[InvalidMetric]("in")
  val shape = StreamletShape.withInlets(inlet)

  override def createLogic = new RunnableGraphStreamletLogic() {
    val flow = FlowWithOffsetContext[InvalidMetric]
      .map { invalidMetric ⇒
        system.log.warning(s"Invalid metric detected! $invalidMetric")
        invalidMetric
      }

    def runnableGraph = {
      sourceWithOffsetContext(inlet).via(flow).to(sinkWithOffsetContext)
    }
  }
}
```

Finally, we have some support classes that we need to process JSON records through Cloudflow pipeline.

**JsonFormats.scala**

```
package sensordata

import java.time.Instant
import java.util.UUID

import scala.util.Try

import spray.json._

trait UUIDJsonSupport extends DefaultJsonProtocol {
  implicit object UUIDFormat extends JsonFormat[UUID] {
    def write(uuid: UUID) = JsString(uuid.toString)

    def read(json: JsValue): UUID = json match {
      case JsString(uuid) ⇒ Try(UUID.fromString(uuid)).getOrElse(deserializationError(s"Expected valid UUID but got '$uuid'."))
      case other          ⇒ deserializationError(s"Expected UUID as JsString, but got: $other")
    }
  }
}

trait InstantJsonSupport extends DefaultJsonProtocol {
  implicit object InstantFormat extends JsonFormat[Instant] {
    def write(instant: Instant) = JsNumber(instant.toEpochMilli)

    def read(json: JsValue): Instant = json match {
      case JsNumber(value) ⇒ Instant.ofEpochMilli(value.toLong)
      case other           ⇒ deserializationError(s"Expected Instant as JsNumber, but got: $other")
    }
  }
}

object MeasurementsJsonSupport extends DefaultJsonProtocol {
  implicit val measurementFormat = jsonFormat3(Measurements.apply)
}

object SensorDataJsonSupport extends DefaultJsonProtocol with UUIDJsonSupport with InstantJsonSupport {
  import MeasurementsJsonSupport._
  implicit val sensorDataFormat = jsonFormat3(SensorData.apply)
}
```

**package.scala**


```
import java.time.Instant

package object sensordata {
  implicit def toInstant(millis: Long): Instant = Instant.ofEpochMilli(millis)
}
```

### Let's join Streamlets to form the Pipeline

As we mentioned above, we need to have a blueprint of the pipeline that will declare which streamlets are joined together to form our pipeline. Here's `blueprint.conf` in `src/main/blueprint` that does specify the connection:

```
blueprint {
  streamlets {
    http-ingress = sensordata.SensorDataHttpIngress
    metrics = sensordata.SensorDataToMetrics
    validation = sensordata.MetricsValidation
    valid-logger = sensordata.ValidMetricLogger
    invalid-logger = sensordata.InvalidMetricLogger
  }

  connections {
    http-ingress.out = [metrics.in]
    metrics.out = [validation.in]
    validation.invalid = [invalid-logger.in]
    validation.valid = [valid-logger.in]
  }
}
```

### Build the Application

Now that we have the application code implemented, let's try to build the application and see what we can do with it locally without deploying in the cluster.

**Verify the Blueprint**

Cloudflow allows you to verify the sanity of the blueprint before you deploy the application on the cluster. It will check for unconnected endpoints, invalid streamlets and other issues related to the structure and semantics of the pipeline.

```
sbt:sensor-data> verifyBlueprint
[info] Streamlet 'sensordata.SensorDataToMetrics' found
[info] Streamlet 'sensordata.MetricsValidation' found
[info] Streamlet 'sensordata.SensorDataHttpIngress' found
[info] Streamlet 'sensordata.ValidMetricLogger' found
[info] Streamlet 'sensordata.InvalidMetricLogger' found
[success] /Users/debasishghosh/lightbend/oss/cloudflow-oss/cloudflow/examples/sensor-data/src/main/blueprint/blueprint.conf verified.
```

**Run the Streamlets Locally**

```
sbt:sensor-data> runLocal
[info] Streamlet 'sensordata.SensorDataToMetrics' found
[info] Streamlet 'sensordata.MetricsValidation' found
[info] Streamlet 'sensordata.SensorDataHttpIngress' found
[info] Streamlet 'sensordata.ValidMetricLogger' found
[info] Streamlet 'sensordata.InvalidMetricLogger' found
[success] /Users/debasishghosh/lightbend/oss/cloudflow-oss/cloudflow/examples/sensor-data/src/main/blueprint/blueprint.conf verified.
[info] Using sandbox configuration from resources/local.conf
---------------------------------- Streamlets ----------------------------------
http-ingress [sensordata.SensorDataHttpIngress]
	- HTTP port [3000]
invalid-logger [sensordata.InvalidMetricLogger]
metrics [sensordata.SensorDataToMetrics]
valid-logger [sensordata.ValidMetricLogger]
validation [sensordata.MetricsValidation]
--------------------------------------------------------------------------------

--------------------------------- Connections ---------------------------------
validation.valid -> valid-logger.in
http-ingress.out -> metrics.in
validation.invalid -> invalid-logger.in
metrics.out -> validation.in
--------------------------------------------------------------------------------

------------------------------------ Output ------------------------------------
Pipeline log output available in file: /var/folders/th/mq68kp9533z31prp3x7fm7rw0000gn/T/local-cloudflow2752564492899894997/local-cloudflow7958636402825157289.log
--------------------------------------------------------------------------------

Running sensor-data  
To terminate, press [ENTER]

```

Let's now move to the cluster mode and install a GKE cluster following the instructions from [Cloudflow Installer Guide](https://github.com/lightbend/cloudflow-installer).

## Install a GKE Cluster

Instructions to install a GKE cluster with Cloudflow is described in details in the [installer Github repo](https://github.com/lightbend/cloudflow-installer). If you want to install a new cluster, clone the repo in your local system and follow the following instructions.

* Run `gcloud init` if this is a new cluster and you are creating a GKE cluster for the first time. This command initializes the gcloud system on your local and performs necessary settings of gcloud credentials. You can have a look at the details [here](https://cloud.google.com/sdk/gcloud/reference/init).

>**Note:** This can be done once you have a valid Google account and have access to a project set up for you by the administrator.

* Create a GKE cluster with a specific name `./gke-create-cluster.sh <cluster-name>`
* Install Cloudflow in the designated cluster `./install-gke.sh  <cluster-name>`

This will take you through the process of installing Cloudflow in the cluster. In the course of this installation, it will ask a few questions regarding the storage class to be installed. The following should be the responses to these:

```
Select a storage class for workloads requiring persistent volumes with access mode 'ReadWriteMany'.
Examples of these are Spark and Flink checkpointing and savepointing.

   NAME                 PROVISIONER                    SUPPORTS RW?                  DEFAULT?                      
1. standard             kubernetes.io/gce-pd           Unknown                       -
2. nfs-client           lightbend.com/nfs              Unknown                       -
> 2

Select a storage class for workloads requiring persistent volumes with access mode 'ReadWriteOnce'.
Examples of these are Kafka, Zookeeper, and Prometheus.

   NAME                 PROVISIONER                    SUPPORTS RW?                  DEFAULT?                      
1. standard             kubernetes.io/gce-pd           Verified                      -
2. nfs-client           lightbend.com/nfs              Unknown                       -
> 1
```

It then takes you through the installation of the Cloudflow operator, the strimzi Kafka operator, the Spark operator and the Flink operator. If things go ok, you will see something like the following on your console:

```
+------------------------------------------------------------------------------------+
|                      Installation of Cloudflow has completed                       |
+------------------------------------------------------------------------------------+

NAME                                                         READY   STATUS              RESTARTS   AGE
cloudflow-flink-flink-operator-8588dbd8f4-gsjnm              1/1     Running             0          70s
cloudflow-operator-57f47676f7-svbvj                          0/1     ContainerCreating   0          3s
cloudflow-sparkoperator-fdp-sparkoperator-69669fdd54-88brw   0/1     ContainerCreating   0          35s
strimzi-cluster-operator-7ff64d4b7-c9r2n                     1/1     Running             0          51s
```

The complete installtion take a bit of a time and you can follow the progress using `kubectl` command. All operators are installed in the namespace `lightbend`. When everything is installed, you will see the following:

```
$ kubectl get pods -n lightbend
NAME                                                         READY   STATUS    RESTARTS   AGE
cloudflow-flink-flink-operator-8588dbd8f4-gsjnm              1/1     Running   0          3m26s
cloudflow-nfs-fdp-nfs-6f8855d44-vdhhj                        1/1     Running   0          109s
cloudflow-operator-57f47676f7-svbvj                          1/1     Running   0          2m19s
cloudflow-sparkoperator-fdp-sparkoperator-69669fdd54-88brw   1/1     Running   0          2m51s
cloudflow-strimzi-entity-operator-5bc9695975-584fb           1/2     Running   0          27s
cloudflow-strimzi-kafka-0                                    2/2     Running   0          79s
cloudflow-strimzi-kafka-1                                    2/2     Running   0          79s
cloudflow-strimzi-kafka-2                                    2/2     Running   0          79s
cloudflow-strimzi-zookeeper-0                                2/2     Running   0          2m16s
cloudflow-strimzi-zookeeper-1                                2/2     Running   0          2m15s
cloudflow-strimzi-zookeeper-2                                2/2     Running   0          2m15s
strimzi-cluster-operator-7ff64d4b7-c9r2n                     1/1     Running   0          3m7s
```

This completes the process of installing the GKE cluster along with all necessary operators on it.

## Publish the Application to GKE Repository

Once the application is built you need to publish the docker image to some registry that stores, manages and secures your application image. In this example we will use the GCloud registry `eu.gcr.io`.

Follow these steps to complete the process of publishing:

* Set up the target environment of publishing in your local build process. Create a file named `target-env.sbt` in the root of your project folder (the same level where you have `build.sbt`). The file needs to have the following 2 lines:

```
ThisBuild / cloudflowDockerRegistry := Some("eu.gcr.io")
ThisBuild / cloudflowDockerRepository := Some("<gcloud project id>")
```

Here `project id` refers to the _ID_ of your Google Cloud Platform project. This can be found on the project page of your Google Cloud Platform.

* Publish the docker image of your project to the above registry. This has 3 steps:
 * First make sure that you have access to the cluster `gcloud container clusters get-credentials <cluster-name>`
 * And that you have access to the Google docker registry `gcloud auth configure-docker`
 * Then publish `sbt buildAndPublish`

Once the image is successfully published, you will see something like the following as output of `buildAndPublish` in your console:

```
[info] 2-89ce8a7: digest: sha256:ee496e8cf3a3d9ab71c3ef4a4929ed8eeb6129845f981c33005942314ad30f18 size: 6804
[info]  
[info] Successfully built and published the following Cloudflow application image:
[info]  
[info]   eu.gcr.io/<gcloud project id>/sensor-data:2-89ce8a7
[info]  
[info] You can deploy the application to a Kubernetes cluster using any of the the following commands:
[info]  
[info]   kubectl-cloudflow deploy eu.gcr.io/<gcloud project id>/sensor-data:2-89ce8a7
```

So now the image is available in the registry for deployment. We will next use Cloudflow CLI to deploy the application to the cluster.

## Deploy Application to the Cluster

This is quite straightforward and the output of `buildAndPublish` actually tells you what command to run for deployment. In addition you need to supply the credentials along with the CLI.

```
$ kubectl-cloudflow deploy -u oauth2accesstoken eu.gcr.io/<gcloud project id>/sensor-data:2-89ce8a7 -p "$(gcloud auth print-access-token)"
```

If the command succeeds, you will see the following output:

```
[Done] Deployment of application `sensor-data` has started.
```

Once all streamlets are up in their pods, you can see them running using the following command:

```
$ kubectl get pods -n sensor-data
NAME                                          READY   STATUS    RESTARTS   AGE
sensor-data-http-ingress-fd9cdb66f-jbsrm      1/1     Running   0          3m1s
sensor-data-invalid-logger-549d687d89-m64l7   1/1     Running   0          3m1s
sensor-data-metrics-6c47bfb489-xd644          1/1     Running   0          3m1s
sensor-data-valid-logger-76884bb775-86pwh     1/1     Running   0          3m1s
sensor-data-validation-7dd858b6c5-lcp2n       1/1     Running   0          3m1s
```

Note that all streamlets run in a namespace that matches the name of the application.

**Congratulations!** You have deployed and started your first Cloudflow application.

## Using some CLI Helpers

Once you have one or more Cloudflow applications started, you can use some CLI helpers to monitor the status of your applications.

* **--help** to see all options available

```
$ kubectl-cloudflow --help
This command line tool can be used to deploy and operate Cloudflow applications.

Usage:
  cloudflow [command]

Available Commands:
  configure                 Configures a deployed Cloudflow application.
  deploy                    Deploys a Cloudflow application to the cluster.
  help                      Help about any command
  install-oc-plugin         Installs the Cloudflow OpenShift CLI `oc` plugin.
  list                      Lists deployed Cloudflow application in the current cluster.
  scale                     Scales a streamlet of a deployed Cloudflow application to the specified number of replicas.
  status                    Gets the status of a Cloudflow application.
  undeploy                  Undeploys a Cloudflow application.
  update-docker-credentials Updates docker registry credentials that are used to pull Cloudflow application images.
  version                   Prints the plugin version.

Flags:
  -h, --help   help for cloudflow

Use "cloudflow [command] --help" for more information about a command.
```

* **list** shows all applications deployed in the cluster

```
$ kubectl-cloudflow list

NAME              NAMESPACE         VERSION           CREATION-TIME     
sensor-data       sensor-data       2-89ce8a7         2019-11-12 12:47:19 +0530 IST
```

* **status** shows details of a running application

```
$ kubectl-cloudflow status sensor-data
Name:             sensor-data
Namespace:        sensor-data
Version:          2-89ce8a7
Created:          2019-11-12 12:47:19 +0530 IST
Status:           Running

STREAMLET         POD                                         STATUS            RESTARTS          READY             
valid-logger      sensor-data-valid-logger-76884bb775-86pwh   Running           0                 True
metrics           sensor-data-metrics-6c47bfb489-xd644        Running           0                 True
invalid-logger    sensor-data-invalid-logger-549d687d89-m64l7 Running           0                 True
validation        sensor-data-validation-7dd858b6c5-lcp2n     Running           0                 True
http-ingress      sensor-data-http-ingress-fd9cdb66f-jbsrm    Running           0                 True
```

## Push data to the Application

Our application uses an http based ingress to ingest data. Follow the following steps to push JSON data through the ingress into the application.

* Get the port details of our ingress streamlet

```
$ kubectl describe pod sensor-data-http-ingress-fd9cdb66f-jbsrm -n sensor-data
Name:               sensor-data-http-ingress-fd9cdb66f-jbsrm
Namespace:          sensor-data
Priority:           0
PriorityClassName:  <none>
Node:               gke-dg-gke-1-default-pool-162a09d5-ddnq/10.132.0.21
Start Time:         Tue, 12 Nov 2019 12:47:20 +0530
Labels:             app.kubernetes.io/component=streamlet
                    app.kubernetes.io/managed-by=cloudflow
                    app.kubernetes.io/name=sensor-data-http-ingress
                    app.kubernetes.io/part-of=sensor-data
                    app.kubernetes.io/version=2-89ce8a7
                    com.lightbend.cloudflow/app-id=sensor-data
                    com.lightbend.cloudflow/streamlet-name=http-ingress
                    pod-template-hash=fd9cdb66f
Annotations:        prometheus.io/scrape: true
Status:             Running
IP:                 10.44.1.6
Controlled By:      ReplicaSet/sensor-data-http-ingress-fd9cdb66f
Containers:
  sensor-data-http-ingress:
    Container ID:  docker://9149cd757094e7ea1b943076048b7efc7aa343da8c2d598bba31295ef3cbfd6b
    Image:         eu.gcr.io/bubbly-observer-178213/sensor-data@sha256:ee496e8cf3a3d9ab71c3ef4a4929ed8eeb6129845f981c33005942314ad30f18
    Image ID:      docker-pullable://eu.gcr.io/bubbly-observer-178213/sensor-data@sha256:ee496e8cf3a3d9ab71c3ef4a4929ed8eeb6129845f981c33005942314ad30f18
    Ports:         3003/TCP, 2048/TCP, 2049/TCP, 2050/TCP
...
```

So one of the POD ports is `3003` - let's set up a port forwarding on it.

* Setup port forwarding for the POD port

```
$ kubectl port-forward sensor-data-http-ingress-fd9cdb66f-jbsrm -n sensor-data 3003:3003
Forwarding from 127.0.0.1:3003 -> 3003
Forwarding from [::1]:3003 -> 3003
Handling connection for 3003
```

* Push data to the application. In order to do that follow the steps:
 * create a json file named `data.json` with the content `{"deviceId":"c75cb448-df0e-4692-8e06-0321b7703992","timestamp":1495545346279,"measurements":{"power":1.7,"rotorSpeed":23.4,"windSpeed":100.1}}`
 * run the following bash script:
```
 for str in $(cat data.json)
do
  echo "Using $str"
  curl -i -X POST http://localhost:3003 -H "Content-Type: application/json" --data "$str"
done
```
* Note that we are using the port number `3003` of `localhost` to which we mapped the POD port. This JSON record will pass through the stages of transformation within the pipeline that we defined in the blueprint.


## Verify the Application works

Check the log of the streamlet `valid-logger` to verify that you get the proper transformed metric.

```
$ kubectl logs sensor-data-valid-logger-76884bb775-86pwh -n sensor-data
```

Towards the end of the log you will see something like the following getting printed out:

```
[INFO] [11/12/2019 07:57:04.369] [akka_streamlet-akka.actor.default-dispatcher-4] [akka.actor.ActorSystemImpl(akka_streamlet)] {"deviceId": "c75cb448-df0e-4692-8e06-0321b7703992", "timestamp": 1495545346279, "name": "rotorSpeed", "value": 23.4}
[INFO] [11/12/2019 07:57:04.375] [akka_streamlet-akka.actor.default-dispatcher-4] [akka.actor.ActorSystemImpl(akka_streamlet)] {"deviceId": "c75cb448-df0e-4692-8e06-0321b7703992", "timestamp": 1495545346279, "name": "power", "value": 1.7}
[INFO] [11/12/2019 07:57:04.375] [akka_streamlet-akka.actor.default-dispatcher-4] [akka.actor.ActorSystemImpl(akka_streamlet)] {"deviceId": "c75cb448-df0e-4692-8e06-0321b7703992", "timestamp": 1495545346279, "name": "windSpeed", "value": 100.1}
```

## Undeploy the Application

```
$ kubectl-cloudflow undeploy sensor-data
```

## Akka implementation of taxi ride application

This example demonstrates how `taxi-ride` application initially implemented in flink can be implemented using Akka.
The implementation is based on [Clustering Akka Streamlet](https://cloudflow.io/docs/current/develop/clustering-akka-streamlet.html)
that coordinate the location of Akka shards and Kafka partitions by using 
[Akka Cluster Kafka Aware sharding](https://akka.io/blog/news/2020/03/18/akka-sharding-kafka-video).
This sharding strategy algorithm uses sharding mechanism used by Kafka partition for the consumed messages.

The implementation combines two streams `TaxiRide` and `TaxiFare` and for each rideID calculates
a fare for a given ride. To use Kafka aware sharding efficiently, both streams shoud come from
the same topic, to ensure that their sharding is the same. The implementation is comprised of two streamlets:

* Merger - combining both streams in one, containing both messages into one (using protobuf [oneof](https://developers.google.com/protocol-buffers/docs/proto#oneof))
* Message processor - doing the actual processing

In the case of Flink we used `RichCoFlatMapFunction` to combine the two streams, here
we are using `RideShare` actor for the same purpose. If we compare implementation of the actor 
with the implementation of Flink's `RichCoFlatMapFunction`, we will see that they are very similar.

Main flow of Flink implementation is very elegant and easy to read   
````
      val rides: DataStream[TaxiRide] =
        readStream(inTaxiRide)
          .filter { ride ⇒
            ride.isStart.booleanValue
          }
          .keyBy("rideId")

      val fares: DataStream[TaxiFare] =
        readStream(inTaxiFare)
          .keyBy("rideId")

      val processed: DataStream[TaxiRideFare] =
        rides
          .connect(fares)
          .flatMap(new EnrichmentFunction)

      writeStream(out, processed)
````

Akka implementation provides the same functionality, with the following 
[code](akkastreams/src/main/scala/taxiride/akka/processor/streamlets/RidesProcessorStreamlet.scala):
````
    def runnableGraph = {
      shardedSourceWithCommittableContext(inTaxiMessage, entity).via(messageFlow).to(committableSink(out))
    }
````
Note that here there is no explicit `keyBy` (we will discuss it later). 
Unlike Flink implementation, where streams are connected before invoking `RichCoFlatMapFunction`, 
in the case of Akka, we do merge as a separate streamlet and then process messages, which can 
contain either ride or fare information.
Finally individual flow looks like [following](akkastreams/src/main/scala/taxiride/akka/processor/streamlets/RidesProcessorStreamlet.scala):

````
    private def messageFlow =
      FlowWithCommittableContext[TaxiRideOrFare]
        .mapAsync(1)(msg ⇒ {
          val actor = sharding.entityRefFor(typeKey, msg.rideId.toString)
          actor.ask[Option[TaxiRideFare]](ref => ProcessMessage(ref, msg))
        }).collect{ case Some(v) => v }

````
Here, `keyBy` is implemented through actor selection - we are using`rideId` is the actor ID.

**NOTE** Flink data model is not based on [key-value pairs](https://ci.apache.org/projects/flink/flink-docs-stable/dev/stream/operators/). 
Therefore, you do not need to physically pack the data set types into keys and values. Keys are 
“virtual”: they are defined as functions over the actual data to guide the grouping operator. Although 
this is also a case in Akka Stream implementation, in order to ensure "locality" of the actor, key 
has to be defined at the message define time and effectively is a key in the Kafka message (Also note
that in oeder for such implementation to work correctly, both stream have to have the same key,
and same amount of patitions). Of course sharding key can be defined differently, but this will lead to to cross instances communications. 
In addition Flink provides two types of streams merging - key based and partion based,
using [CoMap/CoFlatMap](https://ci.apache.org/projects/flink/flink-docs-stable/dev/stream/operators/#comap-coflatmap).
Akka Streams only support key-based one.

Finally comparing `RichCoFlatMapFunction` 
````
  class EnrichmentFunction extends RichCoFlatMapFunction[TaxiRide, TaxiFare, TaxiRideFare] {

    @transient var rideState: ValueState[TaxiRide] = null
    @transient var fareState: ValueState[TaxiFare] = null

    override def open(params: Configuration): Unit = {
      super.open(params)
      rideState = getRuntimeContext.getState(new ValueStateDescriptor[TaxiRide]("saved ride", classOf[TaxiRide]))
      fareState = getRuntimeContext.getState(new ValueStateDescriptor[TaxiFare]("saved fare", classOf[TaxiFare]))
    }

    override def flatMap1(ride: TaxiRide, out: Collector[TaxiRideFare]): Unit = {
      val fare = fareState.value
      if (fare != null) {
        fareState.clear()
        out.collect(new TaxiRideFare(ride.rideId, fare.totalFare))
      } else {
        rideState.update(ride)
      }
    }

    override def flatMap2(fare: TaxiFare, out: Collector[TaxiRideFare]): Unit = {
      val ride = rideState.value
      if (ride != null) {
        rideState.clear()
        out.collect(new TaxiRideFare(ride.rideId, fare.totalFare))
      } else {
        fareState.update(fare)
      }
    }
  }
````
with Actor's [implementation](akkastreams/src/main/scala/taxiride/akka/processor/actors/RidesBehavior.scala)
````
case class ProcessMessage(reply: ActorRef[Option[TaxiRideFare]], record : TaxiRideOrFare)

// Actor
object RideShare{

  def apply(rideid: String): Behavior[ProcessMessage] = {

    def executionstate(rideState: Option[TaxiRide], fareState: Option[TaxiFare]): Behavior[ProcessMessage] = {
      Behaviors.receive { (context, msg) => {
        msg.record.messageType match {
          case MessageType.Ride(ride) =>
            fareState match {
              case Some(fare) =>
                msg.reply ! Some(TaxiRideFare(ride.rideId, fare.totalFare))
                executionstate(rideState, None)
              case _ =>
                msg.reply ! None
                executionstate(Some(ride), fareState)
            }

          case MessageType.Fare(fare) =>
            rideState match {
              case Some(ride) =>
                msg.reply ! Some(TaxiRideFare(ride.rideId, fare.totalFare))
                executionstate(None, fareState)
              case None =>
                msg.reply ! None
                executionstate(rideState, Some(fare))
            }
          case MessageType.Empty =>
            executionstate(rideState, fareState)
        }
      }}
    }
    executionstate(None, None)
  }
}
````
we can see that they are virtually identical. The two main differences are:
* Flink implementation provides a separate method for every stream, currently limited to 2.
Akka implementation is using `match` thus supporting as many message types as required
* Implementation of state. While Flink implementation is using mutable state, backed up by the Flink's
snapshotting, in Akka, state itself is immutable - behavior execution, after a message 
is being handled, return a new behavior containing a new state.

The missing part here is persistence. While Flink implementation provides automatic snapshotting, Akka
requires implementation of an explicit persistence [support](https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html#persistence-example).
Adding persistence to our example requires several things:

* Adding additional libraries to projects, including the following (note here we are using Posgre -JDBC
based presistence. Alternatively we could use Cassandra):
````
val akkaVersion     = "2.6.10"
val jdbcVersion     = "4.0.0"
val postgreVersion  = "42.2.16"
.....
      "com.typesafe.akka"         %% "akka-persistence-typed"       % akkaVersion,
      "com.typesafe.akka"         %% "akka-serialization-jackson"   % akkaVersion,
      "com.typesafe.akka"         %% "akka-persistence-query"       % akkaVersion,
      "com.lightbend.akka"        %% "akka-persistence-jdbc"        % jdbcVersion,
      "org.postgresql"            % "postgresql"                    % postgreVersion,
````

* Refactor the code a little bit to use EventSourcedBehavior, which requires defining of the following:
    * persistenceId is the stable unique identifier for the persistent actor.
    * emptyState defines the State when the entity is first created e.g. a Counter would start with 0 as state.
    * commandHandler defines how to handle command by producing Effects e.g. persisting events, stopping the persistent actor.
    * eventHandler returns the new state given the current state when an event has been persisted.
The full code is [here](akkastreams/src/main/scala/taxiride/akka/processor/actors/persistent/RidesBehavior.scala)
* Deploy Postgre to store persistence. Note that Cloudflow does not provide any build in support
for that, so that you need to do it yourself. For local testing on Mac, you can install it with
Homebrew following this [post](https://flaviocopes.com/postgres-how-to-install/). For cluster,
you can use Postgresql Helm chart, following this [post](https://thenewstack.io/tutorial-deploy-postgresql-on-kubernetes-running-the-openebs-storage-engine/) 
* Create tables for Akka persistence, following [this](https://github.com/akka/akka-persistence-jdbc/blob/v3.5.2/src/test/resources/schema/postgres/postgres-schema.sql) 
* Create application config for Postgress instance following [this](https://github.com/akka/akka-persistence-jdbc/blob/v3.5.2/src/test/resources/postgres-application.conf)
make sure that you merge it with default Cloudflow Akka configuration 
[local](https://github.com/lightbend/cloudflow/blob/master/core/cloudflow-akka/src/main/resources/akka-cluster-local.conf)
or [cluster](https://github.com/lightbend/cloudflow/blob/master/core/cloudflow-akka/src/main/resources/akka-cluster-k8.conf).
See local example [here](akkastreams/src/main/resources/application.conf)

Both Flink and Akka implementation provides the same functionality,
although Akka provides less user friendly syntax. Flink's implementation hides
all of the sharding details by usage of `keyBy` and persistence is done automatically through snapshotting.
On another hand, FLink uses static topology for applications, which means that scaling requires
application restarts, while in the case of Akka, adding additional instance can be done without 
any restarts.

The interesting feature of this example, is that the keys `rideID` do not belong to a fix set,
but rather change all the time. As a result Akka implementation creates and deletes actors (and their state)
all the time. FLink alleviates this problem by using keys sharding - using a single implementation
(actor, in Akka terms) for a set of keys.

We can mimic the same functionality in Akka streams. To do this lets remember, that cloudflow
is leveraging [Kafka aware sharding](https://doc.akka.io/docs/alpakka-kafka/current/cluster-sharding.html),
which means that each instance processes a set of partitions from the topic. In the case of scaling,
the partition as a whole can be moved to a different instance, but can not be split.
This means that we can use actors based on partitions to avoid constant acters creation and deletion.
To achieve this we need to make several changes to our implementation.

* In the original implementation we used two streams, each delivered through the separate topic.
Because it is hard to guarantee that this topics have the same amount of partition, we introduced 
a new message definition [TaxiRideOrFare](datamodel/src/main/protobuf/taxirideorfare.proto), which
leverages protobuf's [`oneof`](https://scalapb.github.io/docs/generated-code/#oneof-fields) to support both `TaxiRide` and `TaxiFare` messages. With this definition
we can use a single topic for both messages.
* Default Kafka [partitioner](https://github.com/apache/kafka/blob/trunk/clients/src/main/java/org/apache/kafka/clients/producer/internals/DefaultPartitioner.java)
uses a 32-bit murmur2 hash to compute the partition id based on the key 
(bytes and the amount of partition).
If we assume that the defalt partitioner is used, we can implement key calculation as following:
````
  def convertRideToPartition(rideID : Long) : String = {
    // hash the keyBytes to choose a partition
    val bytes = BigInt(rideID).toByteArray
    val converted = Utils.toPositive(Utils.murmur2(bytes)) % numberOfPartitions
    converted.toString
  }
````
The result is a slightly different implementation of 
[actor](akkastreams/src/main/scala/taxiride/akka/processor/actors/optimized/RidesBehavior.scala) and
[streamlet](akkastreams/src/main/scala/taxiride/akka/processor/streamlets/optimized/RidesProcessorStreamlet.scala)

Adding persistence in this case is very similar to the previous case. 

This approach allows to have a single actor per kafka partition and consequently make
an application more responsive.
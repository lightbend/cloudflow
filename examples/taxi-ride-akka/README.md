## Akka implementation of taxi ride application

This example demonstrates how the `taxi-ride` application, initially implemented in Flink, can be implemented using Akka.

The implementation combines two streams `TaxiRide` and `TaxiFare` and for each rideID calculates
a fare for a given ride. 

In the case of Flink we used `RichCoFlatMapFunction` to combine the two streams, here
we are using a `RideShare` actor for the same purpose. If we the compare implementation of the actor 
with the implementation of Flink's `RichCoFlatMapFunction`, we will see that they are very similar.
The missing part here is persistence. While Flink implementation provides automatic snapshotting, Akka
requires implementation of an explicit persistence support, which is not currently supported
by Cloudflow (but can be easily added manually).

Main flow of Flink implementation is very elegant and easy to read:
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

Akka implementation provides the same functionality, with the following code:
````
      val rides = shardedSourceWithCommittableContext(inTaxiRide, entity).via(ridesFlow)

      val fares = shardedSourceWithCommittableContext(inTaxiFare, entity).via(faresFlow)

      Merger.source(Seq(rides, fares)).to(committableSink(out))
````
Note that here there is no explicit `keyBy` (we will discuss it later). 
Unlike Flink implementation, where streams are connected before invoking, in the case of
Akka, we process each stream independently and then merge results afterwards.
Finally individual flow looks like following:

````
      FlowWithCommittableContext[TaxiRide]
        .mapAsync(1)(msg ⇒ {
          val actor = sharding.entityRefFor(typeKey, msg.rideId.toString)
          actor.ask[Option[TaxiRideFare]](ref => ProcessRide(ref, msg))
        }).collect{ case Some(v) => v }
````
Here, `keyBy` is implemented through actor selection - we are using`rideId` is the actor ID.
So the implementation provides the same functionality (except for automatic persistence, 
which can be easily added), but with less user friendly syntax. Flink's implementation hides
all of the sharding details by usage of `keyBy`.
On the other hand, Flink uses a static topology for applications, which means that scaling requires
application restarts, while in the case of Akka, adding additional instance can be done without 
any restarts.

The interesting feature of this example, is that the keys `rideID` do not belong to a fixed set,
but rather change all the time. As a result Akka implementation creates and deletes actors
all the time. Flink alleviates this problem by using keys sharding - using a single implementation
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
uses a 32-bit murmur2 hash to compute the partition id based on the key (bytes and the amount of partition).
If we assume that the defalt partitioner is used, we can implement key calculation as following:
````
  def convertRideToPartition(rideID : Long) : String = {
    // hash the keyBytes to choose a partition
    val bytes = BigInt(rideID).toByteArray
    val converted = Utils.toPositive(Utils.murmur2(bytes)) % numberOfPartitions
    converted.toString
  }
````

This approach allows to have a single actor per kafka partition and consequently make
an application more responsive.

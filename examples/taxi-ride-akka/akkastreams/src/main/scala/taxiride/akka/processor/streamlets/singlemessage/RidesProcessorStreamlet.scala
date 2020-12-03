package taxiride.akka.processor.streamlets.singlemessage

import java.util.Properties

import akka.util.Timeout
import akka.cluster.sharding.typed.scaladsl._
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.proto._
import org.apache.kafka.clients.producer._
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.utils.Utils
import taxiride.akka.processor.actors.singlemessage._
import taxiride.datamodel._

import scala.concurrent.duration._


class RidesProcessorStreamlet extends AkkaStreamlet with Clustering {

  val inTaxiMessage = ProtoInlet[TaxiRideOrFare]("in-taximessage")
  val out        = ProtoOutlet[TaxiRideFare]("out", _.rideId.toString)

  val shape = StreamletShape.withInlets(inTaxiMessage).withOutlets(out)

  override protected def createLogic(): AkkaStreamletLogic = new RunnableGraphStreamletLogic() {

    val topic = context.findTopicForPort(inTaxiMessage)
    KafkaSupport.numberOfPartitions(context.runtimeBootstrapServers(topic), topic)

    val typeKey = EntityTypeKey[ProcessMessage]("RideShare")

    val entity = Entity(typeKey)(createBehavior = entityContext => RideShare(entityContext.entityId))

    val sharding = clusterSharding()

    def runnableGraph =
      shardedSourceWithCommittableContext(inTaxiMessage, entity).via(messageFlow).to(committableSink(out))

    implicit val timeout: Timeout = 3.seconds
    private def messageFlow =
      FlowWithCommittableContext[TaxiRideOrFare]
        .mapAsync(1)(msg ⇒ {
          val rideId = if(msg.messageType.isFare) msg.messageType.fare.get.rideId else msg.messageType.ride.get.rideId
          val actor = sharding.entityRefFor(typeKey, KafkaSupport.convertRideToPartition(rideId))
          actor.ask[Option[TaxiRideFare]](ref => ProcessMessage(ref, msg))
        }).collect{ case Some(v) => v }
  }
}

object KafkaSupport{

  private var numberOfPartitions = 1

  private def providerProperties(brokers: String /*, keySerializer: String, valueSerializer: String */): Properties = {
    val props = new Properties
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[ByteArraySerializer].getName)
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[ByteArraySerializer].getName)
    props
  }

  def numberOfPartitions(boostrapServers : String, topic: Topic) : Unit = {

    val producer = new KafkaProducer[Array[Byte], Array[Byte]](
      providerProperties(boostrapServers/*, classOf[ByteArraySerializer].getName, classOf[ByteArraySerializer].getName)*/))
    val partitions = producer.partitionsFor(topic.name)
    numberOfPartitions = partitions.size()
    println(s"Using topic ${topic} with $partitions partitions")
  }

  def convertRideToPartition(rideID : Long) : String = {
    // hash the keyBytes to choose a partition
    val bytes = BigInt(rideID).toByteArray
    val converted = Utils.toPositive(Utils.murmur2(bytes)) % numberOfPartitions
    converted.toString
  }
}
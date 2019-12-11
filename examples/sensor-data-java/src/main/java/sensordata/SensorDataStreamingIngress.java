package sensordata;

import akka.http.javadsl.common.EntityStreamingSupport;
import akka.http.javadsl.marshallers.jackson.Jackson;

import cloudflow.akkastream.AkkaServerStreamlet;

import cloudflow.akkastream.AkkaStreamletLogic;
import cloudflow.akkastream.util.javadsl.HttpServerLogic;
import cloudflow.streamlets.RoundRobinPartitioner;
import cloudflow.streamlets.StreamletShape;
import cloudflow.streamlets.avro.AvroOutlet;

public class SensorDataStreamingIngress extends AkkaServerStreamlet {

  private AvroOutlet<SensorData> out =  AvroOutlet.create("out", SensorData.class)
          .withPartitioner(RoundRobinPartitioner.getInstance());

  public StreamletShape shape() {
   return StreamletShape.createWithOutlets(out);
  }

  public AkkaStreamletLogic createLogic() {
    EntityStreamingSupport ess = EntityStreamingSupport.json();
    return HttpServerLogic.createDefaultStreaming(this, out, Jackson.byteStringUnmarshaller(SensorData.class), ess, getStreamletContext());
  }
}

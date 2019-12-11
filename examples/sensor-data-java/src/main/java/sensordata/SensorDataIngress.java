package sensordata;

import cloudflow.akkastream.AkkaServerStreamlet;

import cloudflow.akkastream.AkkaStreamletLogic;
import cloudflow.akkastream.util.javadsl.HttpServerLogic;

import cloudflow.streamlets.RoundRobinPartitioner;
import cloudflow.streamlets.StreamletShape;
import cloudflow.streamlets.avro.AvroOutlet;

import akka.http.javadsl.marshallers.jackson.Jackson;

public class SensorDataIngress extends AkkaServerStreamlet {
  AvroOutlet<SensorData> out =  AvroOutlet.<SensorData>create("out", SensorData.class)
          .withPartitioner(RoundRobinPartitioner.getInstance());

  public StreamletShape shape() {
   return StreamletShape.createWithOutlets(out);
  }

  public AkkaStreamletLogic createLogic() {
    return HttpServerLogic.createDefault(this, out, Jackson.byteStringUnmarshaller(SensorData.class), getStreamletContext());
  }
}

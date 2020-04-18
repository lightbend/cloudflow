package cloudflow.akkastreamsdoc;

// tag::httpIngress[]
import cloudflow.akkastream.AkkaServerStreamlet;

import cloudflow.akkastream.AkkaStreamletLogic;
import cloudflow.akkastream.util.javadsl.HttpServerLogic;

import cloudflow.streamlets.RoundRobinPartitioner;
import cloudflow.streamlets.StreamletShape;
import cloudflow.streamlets.avro.AvroOutlet;

import akka.http.javadsl.marshallers.jackson.Jackson;

public class DataHttpIngress extends AkkaServerStreamlet {
  AvroOutlet<Data> out =
      AvroOutlet.<Data>create("out", Data.class)
          .withPartitioner(RoundRobinPartitioner.getInstance());

  public StreamletShape shape() {
    return StreamletShape.createWithOutlets(out);
  }

  public AkkaStreamletLogic createLogic() {
    return HttpServerLogic.createDefault(
        this, out, Jackson.byteStringUnmarshaller(Data.class), getContext());
  }
}
// end::httpIngress[]
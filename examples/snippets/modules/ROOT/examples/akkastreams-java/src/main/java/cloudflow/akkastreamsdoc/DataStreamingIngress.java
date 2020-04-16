package cloudflow.akkastreamsdoc;

// tag::httpStreamingIngress[]
import akka.http.javadsl.common.EntityStreamingSupport;
import akka.http.javadsl.marshallers.jackson.Jackson;

import cloudflow.akkastream.AkkaServerStreamlet;

import cloudflow.akkastream.AkkaStreamletLogic;
import cloudflow.akkastream.util.javadsl.HttpWriterLogic;
import cloudflow.streamlets.RoundRobinPartitioner;
import cloudflow.streamlets.StreamletShape;
import cloudflow.streamlets.avro.AvroOutlet;

public class DataStreamingIngress extends AkkaServerStreamlet {

  private AvroOutlet<Data> out =
      AvroOutlet.create("out", Data.class)
          .withPartitioner(RoundRobinPartitioner.getInstance());

  public StreamletShape shape() {
    return StreamletShape.createWithOutlets(out);
  }

  public AkkaStreamletLogic createLogic() {
    EntityStreamingSupport ess = EntityStreamingSupport.json();
    return HttpWriterLogic.createDefaultStreaming(
        this, out, Jackson.byteStringUnmarshaller(Data.class), ess, getContext());
  }
}
// end::httpStreamingIngress[]
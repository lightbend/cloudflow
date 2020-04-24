package cloudflow.akkastreamsdoc;

// tag::merge[]
import cloudflow.akkastream.*;
import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;
import cloudflow.akkastream.javadsl.*;
import cloudflow.akkastream.util.javadsl.*;
import akka.stream.javadsl.*;

import java.util.*;

public class DataMerge extends AkkaStreamlet {
  AvroInlet<Data> inlet1 = AvroInlet.<Data>create("in-0", Data.class);
  AvroInlet<Data> inlet2 = AvroInlet.<Data>create("in-1", Data.class);
  AvroOutlet<Data> outlet = AvroOutlet.<Data>create("out",  d -> d.getKey(), Data.class);

  public StreamletShape shape() {
    return StreamletShape.createWithInlets(inlet1, inlet2).withOutlets(outlet);
  }

  public RunnableGraphStreamletLogic createLogic() {
    return new RunnableGraphStreamletLogic(getContext()) {
      public RunnableGraph<?> createRunnableGraph() {
        return Merger.source(getContext(), inlet1, inlet2).to(getCommittableSink(outlet));
      }
    };
  }
}
// end::merge[]

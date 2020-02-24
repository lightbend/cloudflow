package cloudflow.akkastreamsdoc;

// end::merge[]
import cloudflow.akkastream.*;
import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;
import cloudflow.akkastream.javadsl.*;
import cloudflow.akkastream.util.javadsl.MergeLogic;

import java.util.*;

class DataMerge extends AkkaStreamlet {
  AvroInlet<Data> inlet1 = AvroInlet.<Data>create("in-0", Data.class);
  AvroInlet<Data> inlet2 = AvroInlet.<Data>create("in-1", Data.class);
  AvroOutlet<Data> outlet = AvroOutlet.<Data>create("out",  d -> d.getKey(), Data.class);

  public StreamletShape shape() {
    return StreamletShape.createWithInlets(inlet1, inlet2).withOutlets(outlet);
  }
  public MergeLogic createLogic() {
    List<CodecInlet<Data>> inlets = new ArrayList<CodecInlet<Data>>();
    inlets.add(inlet1);
    inlets.add(inlet2);
    return new MergeLogic(inlets, outlet, getContext());
  }
}
// end::merge[]
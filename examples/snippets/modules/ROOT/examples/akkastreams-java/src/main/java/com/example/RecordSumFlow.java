package com.example;

import akka.NotUsed;
import akka.stream.*;
import akka.stream.javadsl.*;

import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;
import cloudflow.akkastream.*;
import cloudflow.akkastream.javadsl.*;
import cloudflow.akkastream.util.javadsl.*;

import cloudflow.akkastreamsdoc.*;

public class RecordSumFlow extends AkkaStreamlet {

  public static IntegerConfigParameter recordsInWindowParameter = IntegerConfigParameter.create("records-in-window","This value describes how many records of data should be processed together, default 64 records").withDefaultValue(64);

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
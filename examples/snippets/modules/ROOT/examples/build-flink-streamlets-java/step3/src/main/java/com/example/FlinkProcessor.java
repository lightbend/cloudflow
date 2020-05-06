package com.example;

//tag::processor[]
import java.util.List;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSink;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.*;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.TypeHint;
import cloudflow.streamlets.StreamletShape;
import cloudflow.streamlets.avro.*;
import cloudflow.flink.*;

public class FlinkProcessor extends FlinkStreamlet {

  // Step 1: Define inlets and outlets. Note for the outlet you need to specify
  //         the partitioner function explicitly or else RoundRobinPartitioner will
  //         be used : using `name` as the partitioner here
  AvroInlet<Data> in = AvroInlet.<Data>create("in", Data.class);
  AvroOutlet<Data> out = AvroOutlet.<Data>create("out", (Data d) -> Integer.toString(d.getId()), Data.class);

  // Step 2: Define the shape of the streamlet. In this example the streamlet
  //         has 1 inlet and 1 outlet
  @Override public StreamletShape shape() {
    return StreamletShape.createWithInlets(in).withOutlets(out);
  }

  // Step 3: Provide custom implementation of `FlinkStreamletLogic` that defines
  //         the behavior of the streamlet
  @Override public FlinkStreamletLogic createLogic() {
    return new FlinkStreamletLogic(getContext()) {
      @Override public void buildExecutionGraph() {

        DataStream<Data> ins = 
          this.<Data>readStream(in, Data.class)
            .map((Data d) -> d)
            .returns(new TypeHint<Data>(){}.getTypeInfo());

        DataStream<Data> simples = ins.filter((Data d) -> d.getId() % 2 == 0); 
        DataStreamSink<Data> sink = writeStream(out, simples, Data.class);
      }
    };
  }
}
//end::processor[]
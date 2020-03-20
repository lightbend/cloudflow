package com.example;

import org.apache.flink.streaming.api.datastream.DataStream;

import cloudflow.streamlets.StreamletShape;
import cloudflow.streamlets.avro.*;
import cloudflow.flink.*;

public class ReportPrinter extends FlinkStreamlet {
  // 1. Create inlets and outlets
  transient AvroInlet<Report> in = AvroInlet.<Report>create("report-in", Report.class);

  // 2. Define the shape of the streamlet
  @Override public StreamletShape shape() {
    return StreamletShape.createWithInlets(in);
  }

  // 3. Override createLogic to provide StreamletLogic
  @Override public FlinkStreamletLogic createLogic() {
    return new FlinkStreamletLogic(getContext()) {
      public String format(Report r) {
        return new StringBuilder()
	  .append(r.getName())
	  .append("\n\n")
	  .append(r.getDescription())
	  .toString();
      }

      @Override public void buildExecutionGraph() {

        DataStream<Report> ins = 
          this.<Report>readStream(in, Report.class);
        ins
          .map((Report r) -> format(r))
          .print();
      }
    };
  }
}
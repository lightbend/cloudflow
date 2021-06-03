package com.example;

import cloudflow.streamlets.StreamletShape;
import cloudflow.streamlets.avro.*;
import cloudflow.flink.*;

// TODO rename to ReportPrinter
public class ReportPrinterStep2 extends FlinkStreamlet {
  // 1. Create inlets and outlets
  transient AvroInlet<Report> in = AvroInlet.<Report>create("report-in", Report.class);

  // 2. Define the shape of the streamlet
  @Override public StreamletShape shape() {
    return StreamletShape.createWithInlets(in);
  }
  // 3. TODO Override createLogic to provide StreamletLogic
  public FlinkStreamletLogic createLogic() { 
    return new FlinkStreamletLogic(getContext()) {
      public void buildExecutionGraph() {
      }
    };
  }
}
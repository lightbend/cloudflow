package com.example;

import cloudflow.streamlets.StreamletShape;
import cloudflow.streamlets.avro.*;
import cloudflow.flink.*;

public class ReportPrinter extends FlinkStreamlet {
  // 1. Create inlets and outlets
  transient AvroInlet<Report> in = AvroInlet.<Report>create("report-in", Report.class);

  // 2. TODO Define the shape of the streamlet
  public StreamletShape shape() { throw new UnsupportedOperationException("Not Implemented"); }
  // 3. TODO Override createLogic to provide StreamletLogic
  public FlinkStreamletLogic createLogic() { throw new UnsupportedOperationException("Not Implemented"); }
}
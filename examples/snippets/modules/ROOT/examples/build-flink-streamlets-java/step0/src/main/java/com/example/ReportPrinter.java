package com.example;

import cloudflow.streamlets.StreamletShape;
import cloudflow.flink.*;

public class ReportPrinter extends FlinkStreamlet {
  // 1. TODO Create inlets and outlets
  // 2. TODO Define the shape of the streamlet
  public StreamletShape shape() { return StreamletShape.empty(); }
  // 3. TODO Override createLogic to provide StreamletLogic
  public FlinkStreamletLogic createLogic() { 
    return new FlinkStreamletLogic(getContext()) {
      public void buildExecutionGraph() {
      }
    };
  }

}
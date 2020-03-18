package com.example;

import akka.NotUsed;
import akka.stream.*;
import akka.stream.javadsl.*;

import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;
import cloudflow.akkastream.*;
import cloudflow.akkastream.javadsl.*;

public class AtLeastOnceReportPrinter extends AkkaStreamlet {
  // 1. Create inlets and outlets
  AvroInlet<Report> inlet = AvroInlet.<Report>create("report-in", Report.class);

  // 2. Define the shape of the streamlet
  public StreamletShape shape() {
    return StreamletShape.createWithInlets(inlet);
  }
  // 3. Override createLogic to provide StreamletLogic
  public RunnableGraphStreamletLogic createLogic() {
    return new RunnableGraphStreamletLogic(getContext()) {
      public String format(Report report) {
        return report.getName() + "\n\n" +report.getDescription();
      }
      // tag::atLeastOnce[]
      public RunnableGraph<NotUsed> createRunnableGraph() {
        return getSourceWithCommittableContext(inlet)
          .map(report -> {
              System.out.println(format(report));
              return report;
          })
          .toMat(getCommittableSink(), Keep.right());
      }
      // end::atLeastOnce[]
    };
  }
}
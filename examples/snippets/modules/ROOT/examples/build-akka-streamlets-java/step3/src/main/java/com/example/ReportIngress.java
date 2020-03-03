package com.example;

import akka.NotUsed;
import akka.stream.javadsl.*;

import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;

import cloudflow.akkastream.*;
import cloudflow.akkastream.javadsl.*;

import java.time.Duration;
import java.util.*;

public class ReportIngress extends AkkaStreamlet {
  // 1. Create inlets and outlets
  AvroOutlet<Report> outlet = AvroOutlet.<Report>create("out",  r -> r.getId(), Report.class);

  // 2. Define the shape of the streamlet
  public StreamletShape shape() {
    return StreamletShape.createWithOutlets(outlet);
  }
  // 3. TODO Override createLogic to provide StreamletLogic
  public RunnableGraphStreamletLogic createLogic() {
    return new RunnableGraphStreamletLogic(getContext()) {
      public RunnableGraph<NotUsed> createRunnableGraph() {
        List<String> labels = new ArrayList<String>();
        labels.add("ab");
        labels.add("bc");
        return Source.tick(Duration.ofSeconds(0), Duration.ofSeconds(2),new Report("abc", "test", "Just a test", labels))
          .toMat(getPlainSink(outlet), Keep.right());
      }
    };
  }
}
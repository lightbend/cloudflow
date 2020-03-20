package com.example;

import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.api.java.operators.DataSource;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import cloudflow.streamlets.StreamletShape;
import cloudflow.streamlets.avro.*;
import cloudflow.flink.*;
import java.util.*;

public class ReportIngress extends FlinkStreamlet {
  // 1. Create inlets and outlets
  transient AvroOutlet<Report> out = AvroOutlet.<Report>create("out", r -> r.getId(), Report.class);

  // 2. Define the shape of the streamlet
  @Override public StreamletShape shape() {
    return StreamletShape.createWithOutlets(out);
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
        StreamExecutionEnvironment env = getContext().env().getJavaEnv();

        List<String> labels = new ArrayList<String>();
        labels.add("ab");
        labels.add("bc");
        Report report = new Report("abc", "test", "Just a test", labels);

        writeStream(out, env.fromElements(report), Report.class);
      }
    };
  }
}
/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.flink.javadsl;

import java.util.List;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSink;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.*;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.TypeHint;
import cloudflow.streamlets.StreamletShape;
import cloudflow.streamlets.avro.*;
import cloudflow.flink.avro.*;
import cloudflow.flink.*;

public class FlinkProcessor extends FlinkStreamlet {

  // Step 1: Define inlets and outlets. Note for the outlet you need to specify
  //         the partitioner function explicitly or else RoundRobinPartitioner will
  //         be used : using `name` as the partitioner here
  AvroInlet<Data> in = AvroInlet.<Data>create("in", Data.class);
  AvroOutlet<Simple> out = AvroOutlet.<Simple>create("out", (Simple s) -> s.name(), Simple.class);

  // Step 2: Define the shape of the streamlet. In this example the streamlet
  //         has 1 inlet and 1 outlet
  @Override public StreamletShape shape() {
    return StreamletShape.createWithInlets(in).withOutlets(out);
  }

  // Step 3: Provide custom implementation of `FlinkStreamletLogic` that defines
  //         the behavior of the streamlet
  @Override public FlinkStreamletLogic createLogic() {
    return new FlinkStreamletLogic(getStreamletContext()) {
      @Override public void buildExecutionGraph() {

        DataStream<Data> ins =
          this.<Data>readStream(in, Data.class)
            .map((Data d) -> d)
            .returns(new TypeHint<Data>(){}.getTypeInfo());

        DataStream<Simple> simples = ins.map((Data d) -> new Simple(d.name()));
        DataStreamSink<Simple> sink = writeStream(out, simples, Simple.class);
      }
    };
  }
}

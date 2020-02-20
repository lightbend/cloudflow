/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
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

package sensordata;

import java.util.Arrays;

import akka.stream.javadsl.*;
import akka.kafka.ConsumerMessage.Committable;

import akka.NotUsed;

import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;
import cloudflow.akkastream.*;
import cloudflow.akkastream.javadsl.*;

public class SensorDataToMetrics extends AkkaStreamlet {
  AvroInlet<SensorData> in = AvroInlet.<SensorData>create("in", SensorData.class);
  AvroOutlet<Metric> out =
      AvroOutlet.<Metric>create("out", Metric.class)
          .withPartitioner(RoundRobinPartitioner.getInstance());

  public StreamletShape shape() {
    return StreamletShape.createWithInlets(in).withOutlets(out);
  }

  private FlowWithContext<SensorData, Committable, Metric, Committable, NotUsed> flowWithContext() {
    return FlowWithCommittableContext.<SensorData>create()
        .mapConcat(
            data ->
                Arrays.asList(
                    new Metric(
                        data.getDeviceId(),
                        data.getTimestamp(),
                        "power",
                        data.getMeasurements().getPower()),
                    new Metric(
                        data.getDeviceId(),
                        data.getTimestamp(),
                        "rotorSpeed",
                        data.getMeasurements().getRotorSpeed()),
                    new Metric(
                        data.getDeviceId(),
                        data.getTimestamp(),
                        "windSpeed",
                        data.getMeasurements().getWindSpeed())));
  }

  public AkkaStreamletLogic createLogic() {
    return new RunnableGraphStreamletLogic(getContext()) {
      public RunnableGraph createRunnableGraph() {
        return getSourceWithCommittableContext(in)
            .via(flowWithContext())
            .to(getCommittableSink(out));
      }
    };
  }
}

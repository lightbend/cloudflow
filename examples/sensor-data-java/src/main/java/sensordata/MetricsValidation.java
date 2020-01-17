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

import akka.stream.javadsl.*;

import akka.NotUsed;
import akka.actor.*;
import akka.kafka.ConsumerMessage.Committable;
import akka.stream.*;

import com.typesafe.config.Config;

import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;
import cloudflow.akkastream.*;
import cloudflow.akkastream.javadsl.*;
import cloudflow.akkastream.javadsl.util.Either;
import cloudflow.akkastream.util.javadsl.*;

public class MetricsValidation extends AkkaStreamlet {
  AvroInlet<Metric> inlet = AvroInlet.<Metric>create("in", Metric.class);
  AvroOutlet<InvalidMetric> invalidOutlet = AvroOutlet.<InvalidMetric>create("invalid",  m -> m.getMetric().toString(), InvalidMetric.class);
  AvroOutlet<Metric> validOutlet = AvroOutlet.<Metric>create("valid", m -> m.getDeviceId().toString() + m.getTimestamp().toString(), Metric.class);

  public StreamletShape shape() {
   return StreamletShape.createWithInlets(inlet).withOutlets(invalidOutlet, validOutlet);
  }

  public AkkaStreamletLogic createLogic() {
    return new RunnableGraphStreamletLogic(getContext()) {
      public RunnableGraph createRunnableGraph() {
        return getSourceWithOffsetContext(inlet).to(Splitter.<Metric, InvalidMetric, Metric>sink(createFlow(), invalidOutlet, validOutlet, getContext()));
      }
    };
  }

  private FlowWithContext<Metric, Committable, Either<InvalidMetric, Metric>, Committable, NotUsed> createFlow() {
    return FlowWithCommittableContext.<Metric>create().map(metric -> {
        if (!SensorDataUtils.isValidMetric(metric)) return Either.left(new InvalidMetric(metric, "All measurements must be positive numbers!"));
        else return Either.right(metric);
      });
  }
}

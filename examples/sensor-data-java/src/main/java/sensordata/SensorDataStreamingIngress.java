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

import akka.http.javadsl.common.EntityStreamingSupport;
import akka.http.javadsl.marshallers.jackson.Jackson;

import cloudflow.akkastream.AkkaServerStreamlet;

import cloudflow.akkastream.AkkaStreamletLogic;
import cloudflow.akkastream.util.javadsl.HttpServerLogic;
import cloudflow.streamlets.RoundRobinPartitioner;
import cloudflow.streamlets.StreamletShape;
import cloudflow.streamlets.avro.AvroOutlet;

public class SensorDataStreamingIngress extends AkkaServerStreamlet {

  private AvroOutlet<SensorData> out =
      AvroOutlet.create("out", SensorData.class)
          .withPartitioner(RoundRobinPartitioner.getInstance());

  public StreamletShape shape() {
    return StreamletShape.createWithOutlets(out);
  }

  public AkkaStreamletLogic createLogic() {
    EntityStreamingSupport ess = EntityStreamingSupport.json();
    return HttpServerLogic.createDefaultStreaming(
        this, out, Jackson.byteStringUnmarshaller(SensorData.class), ess, getContext());
  }
}

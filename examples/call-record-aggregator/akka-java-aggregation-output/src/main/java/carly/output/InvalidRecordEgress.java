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

package carly.output;

import akka.NotUsed;
import akka.stream.javadsl.*;

import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;
import cloudflow.akkastream.*;
import cloudflow.akkastream.javadsl.*;
import carly.data.*;

public class InvalidRecordEgress extends AkkaStreamlet {
  public AvroInlet<InvalidRecord> in = AvroInlet.create("in", InvalidRecord.class);

  private Object doPrint(final InvalidRecord record) {
    System.out.println(record);
    return record;
  }

  @Override public StreamletShape shape() {
    return StreamletShape.createWithInlets(in);
  }

  @Override
  public AkkaStreamletLogic createLogic() {
    return new RunnableGraphStreamletLogic(getContext()) {

      @Override
      public RunnableGraph<?> createRunnableGraph() {
        return getSourceWithCommittableContext(in)
          .via(FlowWithCommittableContext.<InvalidRecord>create()
                  .map(metric -> {
                    System.out.println(metric);
                    return metric;
                  }))
          .to(getCommittableSink());
      }
    };
  }


}

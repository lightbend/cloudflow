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

package cloudflow.akkastream.util.javadsl;

import java.util.ArrayList;
import java.util.List;

import akka.stream.javadsl.RunnableGraph;
import cloudflow.akkastream.AkkaStreamlet;
import cloudflow.akkastream.javadsl.RunnableGraphStreamletLogic;
import cloudflow.akkastream.testdata.Data;
import cloudflow.streamlets.CodecInlet;
import cloudflow.streamlets.StreamletShape;
import cloudflow.streamlets.avro.AvroInlet;
import cloudflow.streamlets.avro.AvroOutlet;

import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

public class MergeTest extends JUnitSuite {
    private int inletCount = 10;

    @Test
    public void shouldBeTested() {
        TestMerger merger = new TestMerger();
    }

    class TestMerger extends AkkaStreamlet {
        AvroInlet<Data> inlet1 = AvroInlet.<Data>create("in-0", Data.class);
        AvroInlet<Data> inlet2 = AvroInlet.<Data>create("in-1", Data.class);
        AvroOutlet<Data> outlet = AvroOutlet.<Data>create("out",  d -> d.name(), Data.class);

        public StreamletShape shape() {
         return StreamletShape.createWithInlets(inlet1, inlet2).withOutlets(outlet);
        }
        public RunnableGraphStreamletLogic createLogic() {
          return new RunnableGraphStreamletLogic(getContext()) {
            public RunnableGraph createRunnableGraph() {
              return Merger.source(getContext(), inlet1, inlet2).to(getCommittableSink(outlet));
            }
          };
        }
    }
}

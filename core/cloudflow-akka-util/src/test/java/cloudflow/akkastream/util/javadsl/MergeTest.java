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

package cloudflow.akkastream.util.javadsl;
import java.util.*;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import cloudflow.akkastream.AkkaStreamlet;
import cloudflow.akkastream.testdata.Data;
import cloudflow.akkastream.testdata.Data$;
import cloudflow.akkastream.testdata.BadData;
import cloudflow.akkastream.testdata.BadData$;
import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;

import static org.junit.Assert.assertEquals;

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
        public MergeLogicAkka createLogic() {
          List<CodecInlet<Data>> inlets = new ArrayList<CodecInlet<Data>>();
          inlets.add(inlet1);
          inlets.add(inlet2);
          return new MergeLogicAkka(inlets, outlet, getStreamletContext());
        }
    }
}

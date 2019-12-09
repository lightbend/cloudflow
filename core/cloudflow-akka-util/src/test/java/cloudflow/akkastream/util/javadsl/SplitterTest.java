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

import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import akka.NotUsed;
import akka.kafka.ConsumerMessage.CommittableOffset;
import akka.stream.javadsl.*;

import cloudflow.akkastream.AkkaStreamlet;
import cloudflow.akkastream.javadsl.util.*;
import cloudflow.akkastream.testdata.Data;
import cloudflow.akkastream.testdata.Data$;
import cloudflow.akkastream.testdata.BadData;
import cloudflow.akkastream.testdata.BadData$;
import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;

import static org.junit.Assert.assertEquals;

public class SplitterTest extends JUnitSuite {
  @Test
  public void shouldBeTested() {
    TestSplitter splitter = new TestSplitter();
    // TODO add tests
  }


  class TestSplitter extends AkkaStreamlet {
    AvroInlet<Data> inlet = AvroInlet.<Data>create("in", Data.class);
    AvroOutlet<BadData> badOutlet = AvroOutlet.<BadData>create("bad",  d -> d.name(), BadData.class);
    AvroOutlet<Data> goodOutlet = AvroOutlet.<Data>create("good",  d -> d.name(), Data.class);

    public StreamletShape shape() {
     return StreamletShape.createWithInlets(inlet).withOutlets(badOutlet, goodOutlet);
    }

    public SplitterLogicAkka createLogic() {
      return new SplitterLogicAkka(inlet, badOutlet, goodOutlet, getStreamletContext()) {
        public FlowWithContext<Data, CommittableOffset, Either<BadData, Data>, CommittableOffset, NotUsed> createFlow() {
          return FlowWithContext.<Data, CommittableOffset>create().map(d -> Either.right(d));
        }
      };
    }
  }
}

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

package cloudflow.akkastream.javadsl;

import java.util.*;

import scala.concurrent.duration.Duration;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.kafka.ConsumerMessage.CommittableOffset;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.*;
import akka.stream.javadsl.Flow;
import akka.testkit.TestKit;
import cloudflow.akkastream.*;
import cloudflow.akkastream.javadsl.util.*;
import cloudflow.akkastream.testdata.*;
import cloudflow.akkastream.testkit.javadsl.*;
import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;
import cloudflow.streamlets.descriptors.*;

import org.apache.avro.Schema;
import org.scalatest.junit.JUnitSuite;
import org.junit.*;
import static org.junit.Assert.*;

public class AkkaStreamletTest extends JUnitSuite {
  static ActorMaterializer mat;
  static ActorSystem system;

  @BeforeClass
  public static void setUp() throws Exception {
    system = ActorSystem.create();
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() throws Exception {
    TestKit.shutdownActorSystem(system, Duration.create(10, "seconds"), false);
    system = null;
  }

  @Test
  public void anAkkaStreamletShouldProcessDataWhenItIsRun() {
    TestProcessor streamlet = new TestProcessor();
    AkkaStreamletTestKit testkit = AkkaStreamletTestKit.create(system, mat);

    QueueInletTap<Data> in = testkit.makeInletAsTap(streamlet.inlet);
    ProbeOutletTap<Data> out = testkit.makeOutletAsTap(streamlet.outlet);

    in.queue().offer(new Data(1, "a"));
    in.queue().offer(new Data(2, "b"));

    testkit.<Data>run(streamlet, in, out, () -> {
      out.probe().expectMsg(new Pair<String, Data>("a", new Data(1, "a")));
      return out.probe().expectMsg(new Pair<String, Data>("b", new Data(2, "b")));
    });

    out.probe().expectMsg(Completed.completed());
  }

  @Test
  public void anAkkaStreamletShouldBeAbleToDefineAndUseConfigurationParameters() {
    TestConfigParametersProcessor streamlet = new TestConfigParametersProcessor();
    AkkaStreamletTestKit testkit =
      AkkaStreamletTestKit
        .create(system, mat)
        .withConfigParameterValues(ConfigParameterValue.create(nameFilter, "b"));

    QueueInletTap<Data> in = testkit.makeInletAsTap(streamlet.inlet);
    ProbeOutletTap<Data> out = testkit.makeOutletAsTap(streamlet.outlet);

    in.queue().offer(new Data(1, "a"));
    in.queue().offer(new Data(2, "b"));

    testkit.<Data>run(streamlet, in, out, () -> {
      return out.probe().expectMsg(new Pair<String, Data>("b", new Data(2, "b")));
    });

    out.probe().expectMsg(Completed.completed());
  }

  class TestProcessor extends AkkaStreamlet {
    AvroInlet<Data> inlet = AvroInlet.<Data>create("in", Data.class);
    AvroOutlet<Data> outlet = AvroOutlet.<Data>create("out", d -> d.name(), Data.class);

    public StreamletShape shape() {
      return StreamletShape.createWithInlets(inlet).withOutlets(outlet);
    }

    public AkkaStreamletLogic createLogic() {
      return new AkkaStreamletLogic(getStreamletContext()) {
        public void run() {
          getSourceWithOffsetContext(inlet)
            .via(Flow.<Pair<Data, CommittableOffset>>create()) // no-op flow
            .to(getSinkWithOffsetContext(outlet))
            .run(materializer());
        }
      };
    }
  }

  public static StringConfigParameter nameFilter =
    StringConfigParameter.create(
      "name-filter-value",
      "Filters out the data in the stream that matches this name."
    );

  class TestConfigParametersProcessor extends AkkaStreamlet {
    AvroInlet<Data> inlet = AvroInlet.<Data>create("in", Data.class);
    AvroOutlet<Data> outlet = AvroOutlet.<Data>create("out", d -> d.name(), Data.class);

    public StreamletShape shape() {
      return StreamletShape.createWithInlets(inlet).withOutlets(outlet);
    }

    public ConfigParameter[] defineConfigParameters() {
      return new ConfigParameter[]{ nameFilter };
    }

    public AkkaStreamletLogic createLogic() {
      return new AkkaStreamletLogic(getStreamletContext()) {
        public void run() {
          String configuredNameToFilterFor = streamletConfig().getString(nameFilter.getKey());

          getSourceWithOffsetContext(inlet)
            .filter(data -> data.name().equals(configuredNameToFilterFor))
            .to(getSinkWithOffsetContext(outlet)).run(materializer());
        }
      };
    }
  }
}

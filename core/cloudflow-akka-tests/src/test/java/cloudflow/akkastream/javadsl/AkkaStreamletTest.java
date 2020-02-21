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

package cloudflow.akkastream.javadsl;

import akka.Done;
import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.kafka.ConsumerMessage;
import akka.kafka.ConsumerMessage.CommittableOffset;
import akka.kafka.ConsumerMessage.Committable;
import akka.stream.javadsl.Flow;
import akka.testkit.TestKit;
import cloudflow.akkastream.*;
import cloudflow.akkastream.testdata.*;
import cloudflow.akkastream.testkit.javadsl.*;
import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;

import org.scalatestplus.junit.JUnitSuite;
import org.junit.*;
import scala.concurrent.duration.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

public class AkkaStreamletTest extends JUnitSuite {
  static ActorSystem system;

  @BeforeClass
  public static void setUp() throws Exception {
    system = ActorSystem.create();
  }

  @AfterClass
  public static void tearDown() throws Exception {
    TestKit.shutdownActorSystem(system, Duration.create(10, "seconds"), false);
    system = null;
  }

  @Test
  public void anAkkaStreamletShouldProcessDataWhenItIsRun() {
    TestProcessor streamlet = new TestProcessor();
    AkkaStreamletTestKit testkit = AkkaStreamletTestKit.create(system);

    QueueInletTap<Data> in = testkit.makeInletAsTap(streamlet.inlet);
    ProbeOutletTap<Data> out = testkit.makeOutletAsTap(streamlet.outlet);

    in.queue().offer(new Data(1, "a"));
    in.queue().offer(new Data(2, "b"));

    testkit.<Data>run(
        streamlet,
        in,
        out,
        () -> {
          out.probe().expectMsg(new Pair<String, Data>("a", new Data(1, "a")));
          return out.probe().expectMsg(new Pair<String, Data>("b", new Data(2, "b")));
        });

    out.probe().expectMsg(Completed.completed());
  }

  @Test
  public void anAkkaStreamletShouldBeAbleToDefineAndUseConfigurationParameters() {
    TestConfigParametersProcessor streamlet = new TestConfigParametersProcessor();
    AkkaStreamletTestKit testkit =
        AkkaStreamletTestKit.create(system)
            .withConfigParameterValues(ConfigParameterValue.create(nameFilter, "b"));

    QueueInletTap<Data> in = testkit.makeInletAsTap(streamlet.inlet);
    ProbeOutletTap<Data> out = testkit.makeOutletAsTap(streamlet.outlet);

    in.queue().offer(new Data(1, "a"));
    in.queue().offer(new Data(2, "b"));

    testkit.<Data>run(
        streamlet,
        in,
        out,
        () -> {
          return out.probe().expectMsg(new Pair<String, Data>("b", new Data(2, "b")));
        });

    out.probe().expectMsg(Completed.completed());
  }

  @Test
  public void anAkkaStreamletShouldWriteToAVolumeWhenItIsRun() throws IOException {
    String volumeMountName = "data-mount";
    Path mountPath = Files.createTempFile("test", UUID.randomUUID().toString());
    FileWritingProcessor streamlet = new FileWritingProcessor(volumeMountName);
    AkkaStreamletTestKit testkit =
        AkkaStreamletTestKit.create(system, mat)
            .withVolumeMounts(
                VolumeMount.createReadWriteMany(volumeMountName, mountPath.toString()));

    QueueInletTap<Data> in = testkit.makeInletAsTap(streamlet.inlet);
    ProbeOutletTap<Data> out = testkit.makeOutletAsTap(streamlet.outlet);

    Data dataIn = new Data(1, "a");
    in.queue().offer(dataIn);

    testkit.<Data>run(
        streamlet,
        in,
        out,
        () -> {
          out.probe().receiveN(1);
          try {
            Assert.assertArrayEquals(dataIn.toString().getBytes(), Files.readAllBytes(mountPath));
          } catch (IOException e) {
            throw new AssertionError("Cannot read file: " + mountPath, e);
          }
          return Done.done();
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
      return new AkkaStreamletLogic(getContext()) {
        public void run() {
          getSourceWithCommittableContext(inlet)
              .via(Flow.<Pair<Data, Committable>>create()) // no-op flow
              .to(getCommittableSink(outlet))
              .run(materializer());
        }
      };
    }
  }

  class FileWritingProcessor extends AkkaStreamlet {

    private final String volumeMountName;

    FileWritingProcessor(String volumeMountName) {
      this.volumeMountName = volumeMountName;
    }

    AvroInlet<Data> inlet = AvroInlet.<Data>create("in", Data.class);
    AvroOutlet<Data> outlet = AvroOutlet.<Data>create("out", Data.class);

    public StreamletShape shape() {
      return StreamletShape.createWithInlets(inlet).withOutlets(outlet);
    }

    @Override
    public VolumeMount[] defineVolumeMounts() {
      return new VolumeMount[] {VolumeMount.createReadWriteMany(volumeMountName, "path")};
    }

    public AkkaStreamletLogic createLogic() {
      Path mountedPath = getContext().getMountedPath(defineVolumeMounts()[0]);
      return new AkkaStreamletLogic(getContext()) {
        public void run() {
          getSourceWithCommittableContext(inlet)
              .via(
                  Flow.<Pair<Data, ConsumerMessage.Committable>>create()
                      .map(
                          dataIn -> {
                            Files.write(
                                mountedPath,
                                dataIn.first().toString().getBytes(),
                                StandardOpenOption.SYNC);
                            return dataIn;
                          }))
              .to(getCommittableSink(outlet))
              .run(system());
        }
      };
    }
  }

  public static StringConfigParameter nameFilter =
      StringConfigParameter.create(
          "name-filter-value", "Filters out the data in the stream that matches this name.");

  class TestConfigParametersProcessor extends AkkaStreamlet {
    AvroInlet<Data> inlet = AvroInlet.<Data>create("in", Data.class);
    AvroOutlet<Data> outlet = AvroOutlet.<Data>create("out", d -> d.name(), Data.class);

    public StreamletShape shape() {
      return StreamletShape.createWithInlets(inlet).withOutlets(outlet);
    }

    public ConfigParameter[] defineConfigParameters() {
      return new ConfigParameter[] {nameFilter};
    }

    public AkkaStreamletLogic createLogic() {
      return new AkkaStreamletLogic(getContext()) {
        public void run() {
          String configuredNameToFilterFor = streamletConfig().getString(nameFilter.getKey());

          getSourceWithCommittableContext(inlet)
              .filter(data -> data.name().equals(configuredNameToFilterFor))
              .to(getCommittableSink(outlet))
              .run(system());
        }
      };
    }
  }
}

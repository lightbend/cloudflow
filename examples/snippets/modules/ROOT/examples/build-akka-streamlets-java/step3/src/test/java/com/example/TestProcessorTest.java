package com.example;

import akka.japi.Pair;
//tag::imports[]
import org.junit.*;

import akka.NotUsed;
import akka.actor.*;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.testkit.*;

import cloudflow.streamlets.*;
import cloudflow.akkastream.*;
import cloudflow.akkastream.javadsl.*;

import cloudflow.akkastream.testkit.javadsl.*;

import scala.concurrent.duration.Duration;
import scala.compat.java8.FutureConverters; 
//end::imports[]

//tag::init[]
class TestProcessorTest {

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
  //end::init[]

  //tag::test[]
  @Test
  public void testFlowProcessor() {
    TestProcessor sfp = new TestProcessor();

    // 1. instantiate the testkit
    AkkaStreamletTestKit testkit = AkkaStreamletTestKit.create(system);

    // 2. Setup inlet taps that tap the inlet ports of the streamlet
    QueueInletTap<Data> in = testkit.makeInletAsTap(sfp.inlet);

    // 3. Setup outlet probes for outlet ports
    ProbeOutletTap<Data> out = testkit.makeOutletAsTap(sfp.outlet);

    // 4. Push data into inlet ports
    in.queue().offer(new Data(1, "a"));
    in.queue().offer(new Data(2, "b"));

    // 5. Run the streamlet using the testkit and the setup inlet taps and outlet probes
    testkit.<Data>run(sfp, in, out, () -> {
      // 6. Assert
      out.probe().expectMsg(new Pair<String, Data>("a", new Data(1, "a")));
      return out.probe().expectMsg(new Pair<String, Data>("b", new Data(2, "b")));
    });

    // 6. Assert
    out.probe().expectMsg(Completed.completed());
  }
  //end::test[]

//tag::init[]
}
//end::init[]

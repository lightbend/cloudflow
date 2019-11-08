package sensordata;

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
import cloudflow.akkastream.testkit.OutletTap;
import cloudflow.akkastream.testkit.javadsl.*;

import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;
import cloudflow.streamlets.descriptors.*;

import org.apache.avro.Schema;
import org.scalatest.junit.JUnitSuite;
import org.junit.*;
import static org.junit.Assert.*;

public class MetricsValidationTest extends JUnitSuite {
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
  public void shouldProcessInvalidMetric() {
    MetricsValidation streamlet = new MetricsValidation();
    AkkaStreamletTestKit testkit = AkkaStreamletTestKit.create(system, mat);

    QueueInletTap<Metric> in = testkit.makeInletAsTap(streamlet.inlet);
    ProbeOutletTap<Metric> valid = testkit.makeOutletAsTap(streamlet.validOutlet);
    ProbeOutletTap<InvalidMetric> invalid = testkit.makeOutletAsTap(streamlet.invalidOutlet);
    long timestamp = System.currentTimeMillis();
    Metric metric = new Metric("dev1", timestamp, "metric-name", -1.0d);
    in.queue().offer(metric);
    InvalidMetric expectedInvalidMetric = new InvalidMetric(metric, "All measurements must be positive numbers!");

    String expectedKey = streamlet.invalidOutlet.partitioner().apply(expectedInvalidMetric);
    List<OutletTap<?>> outlets = Arrays.asList(new OutletTap[] {valid, invalid}); 

    testkit.run(streamlet, in, outlets, () -> {
      return invalid.probe().expectMsg(new Pair<String, InvalidMetric>(expectedKey, expectedInvalidMetric));
    });

    invalid.probe().expectMsg(Completed.completed());
  }
}

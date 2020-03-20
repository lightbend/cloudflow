package com.example;

import akka.japi.Pair;
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

  @Test
  public void testFlowProcessor() {

    //tag::config-value[]
    AkkaStreamletTestKit testkit = AkkaStreamletTestKit.create(system, mat).withConfigParameterValues(ConfigParameterValue.create(RecordSumFlow.recordsInWindowParameter, "20"));
    //end::config-value[]
  
  }

}

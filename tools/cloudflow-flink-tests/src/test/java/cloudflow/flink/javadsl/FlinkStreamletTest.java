/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
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

package cloudflow.flink.javadsl;

import org.junit.*;
import static org.junit.Assert.*;
import junit.framework.TestCase;

import org.scalatestplus.junit.JUnitSuite;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import cloudflow.flink.testkit.*;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import cloudflow.flink.*;
import cloudflow.flink.avro.*;

public class FlinkStreamletTest extends JUnitSuite {

  @Test
  public void shouldProcessDataWhenItIsRun() {
    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    FlinkTestkit testkit = new FlinkTestkit() {};
    FlinkProcessor streamlet = new FlinkProcessor();

    // build data and send to inlet tap
    List<Integer> range = IntStream.rangeClosed(1, 10).boxed().collect(Collectors.toList());
    List<Data> data =
        range.stream()
            .map((Integer i) -> new Data(i, "name" + i.toString()))
            .collect(Collectors.toList());

    // setup inlet tap on inlet port
    FlinkInletTap<Data> in =
        testkit.<Data>getInletAsTap(
            streamlet.in,
            env.<Data>addSource(
                FlinkSource.<Data>collectionSourceFunction(data),
                TypeInformation.<Data>of(Data.class)),
            Data.class);

    // setup outlet tap on outlet port
    FlinkOutletTap<Simple> out = testkit.getOutletAsTap(streamlet.out, Simple.class);

    testkit.run(streamlet, Collections.singletonList(in), Collections.singletonList(out), env);

    assertTrue(TestFlinkStreamletContext.result().contains((new Simple("name1").toString())));
    assertEquals(TestFlinkStreamletContext.result().size(), 10);
  }
}

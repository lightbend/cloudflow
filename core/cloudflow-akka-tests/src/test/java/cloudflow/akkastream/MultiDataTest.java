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

package cloudflow.akkastream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.CoreMatchers.*;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

import java.util.Arrays;
import java.util.Collections;

/** Try the Java API to ensure types are sound for Java. */
public class MultiDataTest extends JUnitSuite {

  @Test
  public void twoLists() {
    MultiData2<String, Object> data =
        MultiData2.create(Arrays.asList("A", "B"), Arrays.asList(456, 323, 32));
    assertThat(data.getData1().size(), is(2));
    assertThat(data.data1().size(), is(2));
    assertThat(data.getData2().size(), is(3));
    assertThat(data.data2().size(), is(3));
  }

  @Test
  public void data1EmptyList() {
    MultiData2<Object, String> data =
        MultiData2.create(Collections.emptyList(), Arrays.asList("A", "B"));
    assertThat(data.data2().size(), is(2));
  }

  @Test
  public void data2EmptyList() {
    MultiData2<String, Object> data =
        MultiData2.create(Arrays.asList("A", "B"), Collections.emptyList());
    assertThat(data.data1().size(), is(2));
  }

  @Test
  public void data1() {
    MultiData2<String, Object> data = MultiData2.createData1(Arrays.asList("ET", "TG", "WE"));
    assertThat(data.data1().size(), is(3));
    assertThat(data.data2().size(), is(0));
  }

  @Test
  public void data2() {
    MultiData2<Object, String> data = MultiData2.createData2(Arrays.asList("A", "B", "C", "D"));
    assertThat(data.data1().size(), is(0));
    assertThat(data.data2().size(), is(4));
  }
}

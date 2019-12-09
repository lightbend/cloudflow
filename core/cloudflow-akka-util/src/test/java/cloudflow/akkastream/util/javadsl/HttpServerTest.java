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

import akka.http.javadsl.common.EntityStreamingSupport;
import akka.http.javadsl.unmarshalling.*;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.util.ByteString;
import cloudflow.akkastream.*;
import cloudflow.akkastream.testdata.Data;
import cloudflow.akkastream.testdata.Data$;
import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;

import static org.junit.Assert.assertEquals;

public class HttpServerTest extends JUnitSuite {

  @Test
  public void shouldBeTested() {
    TestHttpServer ingress = new TestHttpServer();
    // TODO add tests
  }


  class TestHttpServer extends AkkaServerStreamlet {
    AvroOutlet<Data> outlet = AvroOutlet.<Data>create("out",  d -> d.name(), Data.class);
    Unmarshaller<ByteString, Data> fbu = Jackson.byteStringUnmarshaller(Data.class);

    public StreamletShape shape() {
      return StreamletShape.createWithOutlets(outlet);
    }

    public HttpServerLogicAkka createLogic() {
      return HttpServerLogicAkka.createDefault(this, outlet, fbu, getStreamletContext());
    }
  }

  class TestStreamingHttpServer extends AkkaServerStreamlet {
    AvroOutlet<Data> outlet = AvroOutlet.<Data>create("out",  d -> d.name(), Data.class);
    Unmarshaller<ByteString, Data> fbu = Jackson.byteStringUnmarshaller(Data.class);
    EntityStreamingSupport entityStreamingSupport = EntityStreamingSupport.json();

    public StreamletShape shape() {
      return StreamletShape.createWithOutlets(outlet);
    }

    public HttpServerLogicAkka createLogic() {
      return HttpServerLogicAkka.createDefaultStreaming(this, outlet, fbu, entityStreamingSupport, getStreamletContext());
    }
  }
}

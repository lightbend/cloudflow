package cloudflow.akkastreamsdoc;

import java.util.Collections;
import java.util.List;

import akka.stream.javadsl.*;

import akka.NotUsed;
import akka.actor.*;
import akka.kafka.ConsumerMessage.Committable;
import akka.stream.*;

import com.typesafe.config.Config;

// tag::sink2[]
import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;
import cloudflow.akkastream.*;
import cloudflow.akkastream.javadsl.*;
import cloudflow.akkastream.util.javadsl.*;

import akka.japi.Pair;

public class MultiOutlet extends AkkaStreamlet {
  AvroInlet<Data> inlet = AvroInlet.<Data>create("in", Data.class);
  AvroOutlet<DataInvalid> invalidOutlet =
      AvroOutlet.<DataInvalid>create(
          "invalid", d -> d.getKey(), DataInvalid.class);
  AvroOutlet<Data> validOutlet =
      AvroOutlet.<Data>create(
          "valid", d -> RoundRobinPartitioner.apply(d), Data.class);

  public StreamletShape shape() {
    return StreamletShape.createWithInlets(inlet).withOutlets(invalidOutlet, validOutlet);
  }

  public AkkaStreamletLogic createLogic() {
    return new RunnableGraphStreamletLogic(getContext()) {
      public RunnableGraph createRunnableGraph() {
        return getSourceWithCommittableContext(inlet)
                .map(data -> {
                        final List<DataInvalid> invalid;
                        final List<Data> valid;
                        if (data.getValue() < 0) {
                            invalid = Collections.singletonList(new DataInvalid(data.getKey(), data.getValue(), "All data must be positive numbers!"));
                            valid = Collections.emptyList();
                        } else {
                            invalid = Collections.emptyList();
                            valid = Collections.singletonList(data);
                        }
                        return Pair.create(invalid, valid);
                })
                .to(MultiOutlet.sink2(invalidOutlet, validOutlet, getContext()));
      }
    };
  }
}
// end::sink2[]

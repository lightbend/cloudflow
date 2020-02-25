package cloudflow.akkastreamsdoc;

import akka.stream.javadsl.*;

import akka.NotUsed;
import akka.actor.*;
import akka.kafka.ConsumerMessage.Committable;
import akka.stream.*;

import com.typesafe.config.Config;

// tag::splitter[]
import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;
import cloudflow.akkastream.*;
import cloudflow.akkastream.javadsl.*;
import cloudflow.akkastream.javadsl.util.Either;
import cloudflow.akkastream.util.javadsl.*;

public class DataSplitter extends AkkaStreamlet {
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
        return getSourceWithCommittableContext(inlet).to(Splitter.sink(createFlow(), invalidOutlet, validOutlet, getContext()));
      }
    };
  }

  public FlowWithContext<Data, Committable, Either<DataInvalid, Data>, Committable, NotUsed> createFlow() {
    return FlowWithContext.<Data, Committable>create()
      .map(data -> {	
        if (data.getValue() < 0) return Either.left(new DataInvalid(data.getKey(), data.getValue(), "All data must be positive numbers!"));	
        else return Either.right(data);	
      });	
  }
}
// end::splitter[]

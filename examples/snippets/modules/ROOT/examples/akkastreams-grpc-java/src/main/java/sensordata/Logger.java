package sensordata;

import akka.stream.javadsl.RunnableGraph;

import cloudflow.akkastream.AkkaStreamlet;
import cloudflow.akkastream.AkkaStreamletLogic;
import cloudflow.akkastream.javadsl.RunnableGraphStreamletLogic;
import cloudflow.streamlets.StreamletShape;
import cloudflow.streamlets.proto.javadsl.ProtoInlet;

import scala.Option;
import sensordata.grpc.SensorData;

public class Logger extends AkkaStreamlet {
    private final ProtoInlet<SensorData> inlet = (ProtoInlet<SensorData>) ProtoInlet.create("in", SensorData.class)
            .withErrorHandler((inBytes, throwable) -> {
                        context().system().log().error(String.format("an exception occurred on inlet: %s -> (hex string) %h", throwable.getMessage(), inBytes));
                        return Option.apply(null); // skip the element
                    }
            );    

    @Override
    public StreamletShape shape() {
        return StreamletShape.createWithInlets(inlet);
    }
    @Override
    public AkkaStreamletLogic createLogic() {
        return new RunnableGraphStreamletLogic(getContext()) {
            @Override
            public RunnableGraph<?> createRunnableGraph() {
                return getSourceWithCommittableContext(inlet)
                        .map(d -> {
                            System.out.println("Saw " + d);
                            return d;
                        })
                        .to(getCommittableSink());
            }
        };
    }


}

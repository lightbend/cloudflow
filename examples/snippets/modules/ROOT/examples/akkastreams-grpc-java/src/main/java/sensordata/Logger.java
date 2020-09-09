package sensordata;

import akka.stream.javadsl.RunnableGraph;

import cloudflow.akkastream.AkkaStreamlet;
import cloudflow.akkastream.AkkaStreamletLogic;
import cloudflow.akkastream.javadsl.RunnableGraphStreamletLogic;
import cloudflow.streamlets.StreamletShape;
import cloudflow.streamlets.proto.javadsl.ProtoInlet;

import sensordata.grpc.SensorData;

public class Logger extends AkkaStreamlet {
    private final ProtoInlet<SensorData> inlet = new ProtoInlet<SensorData>(
            "in",
            SensorData.class,
            true
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

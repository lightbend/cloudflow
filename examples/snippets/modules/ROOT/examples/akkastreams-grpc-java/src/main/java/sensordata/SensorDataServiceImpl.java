package sensordata;

import cloudflow.akkastream.WritableSinkRef;
import sensordata.grpc.SensorData;
import sensordata.grpc.SensorDataService;
import sensordata.grpc.SensorReply;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class SensorDataServiceImpl implements SensorDataService {
    private final WritableSinkRef<SensorData> sinkRef;

    public SensorDataServiceImpl(WritableSinkRef<SensorData> sinkRef) {
        this.sinkRef = sinkRef;
    }

    @Override
    public CompletionStage<SensorReply> provide(SensorData in) {
        return sinkRef.writeJava(in).thenApply(i -> SensorReply.newBuilder().setMessage("Received " + in.getPayload()).build());
    }
}

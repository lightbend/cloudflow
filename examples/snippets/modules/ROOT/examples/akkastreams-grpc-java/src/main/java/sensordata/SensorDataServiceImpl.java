package sensordata;

import sensordata.grpc.SensorData;
import sensordata.grpc.SensorDataService;
import sensordata.grpc.SensorReply;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class SensorDataServiceImpl implements SensorDataService {
    @Override
    public CompletionStage<SensorReply> provide(SensorData in) {
        return CompletableFuture.completedFuture(SensorReply.newBuilder().setMessage("Received " + in.getPayload()).build());
    }
}

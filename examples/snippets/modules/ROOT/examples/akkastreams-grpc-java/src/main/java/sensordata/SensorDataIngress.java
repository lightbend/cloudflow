package sensordata;

import akka.grpc.javadsl.ServerReflection;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.japi.Function;

import cloudflow.akkastream.*;
import cloudflow.akkastream.util.javadsl.GrpcServerLogic;
import cloudflow.streamlets.*;
import cloudflow.streamlets.proto.javadsl.ProtoOutlet;

import sensordata.grpc.SensorData;
import sensordata.grpc.SensorDataService;
import sensordata.grpc.SensorDataServiceHandlerFactory;

import java.util.List;
import java.util.concurrent.CompletionStage;

public class SensorDataIngress extends AkkaServerStreamlet {
    public final ProtoOutlet<SensorData> out =
            new ProtoOutlet<SensorData>("out", RoundRobinPartitioner.getInstance(), SensorData.class);

    public StreamletShape shape() {
        return StreamletShape.createWithOutlets(out);
    }

    //tag::logic[]
    public AkkaStreamletLogic createLogic() {
        return new GrpcServerLogic(this, getContext()) {
            public List<Function<HttpRequest, CompletionStage<HttpResponse>>> handlers() {
                return List.of(
                        SensorDataServiceHandlerFactory.partial(new SensorDataServiceImpl(sinkRef(out)), SensorDataService.name, system()),
                        ServerReflection.create(List.of(SensorDataService.description), system()));
            }
        };
    }
    //end::logic[]
}

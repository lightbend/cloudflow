package sensordata;

//tag::logic[]
import akka.grpc.javadsl.ServerReflection;
//end::logic[]
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.japi.Function;

import cloudflow.akkastream.*;
//tag::logic[]
import cloudflow.akkastream.util.javadsl.GrpcServerLogic;
//end::logic[]
import cloudflow.streamlets.*;
import cloudflow.streamlets.proto.javadsl.ProtoOutlet;

import sensordata.grpc.SensorData;
import sensordata.grpc.SensorDataService;
//tag::logic[]
import sensordata.grpc.SensorDataServiceHandlerFactory;

//end::logic[]

import java.util.List;
import java.util.concurrent.CompletionStage;

//tag::logic[]
public class SensorDataIngress extends AkkaServerStreamlet {
    // ...

//end::logic[]
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
}
//end::logic[]
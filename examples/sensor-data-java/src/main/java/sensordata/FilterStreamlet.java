package sensordata;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorMaterializer;
import akka.stream.alpakka.file.DirectoryChange;
import akka.stream.alpakka.file.javadsl.DirectoryChangesSource;
import akka.stream.javadsl.*;
import akka.util.ByteString;
import com.typesafe.config.Config;
import cloudflow.akkastream.*;
import cloudflow.akkastream.javadsl.RunnableGraphStreamletLogic;
import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.AvroInlet;
import cloudflow.streamlets.avro.AvroOutlet;
import scala.concurrent.duration.FiniteDuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class FilterStreamlet extends AkkaStreamlet {

    // Declare the volume mount
    private final VolumeMount referenceFiles = VolumeMount.createReadWriteMany("configuration", "/mnt/data");

    // Make the filter filename configurable
    private final StringConfigParameter filterFilenameConfig = StringConfigParameter.create(
                "filter-filename",
                "Name of the text file in the volume mount directory that contains the list of device ids to filter out."
            ).withDefaultValue("device-ids.txt");

    // Make polling interval configurable
    private final IntegerConfigParameter filterPollingInterval = IntegerConfigParameter.create(
                "filter-pollinginterval",
                "The interval in seconds the streamlet should check for updates to the filter file."
            ).withDefaultValue(10);

    @Override
    public VolumeMount[] defineVolumeMounts() {
        return new VolumeMount[] { referenceFiles };
    }

    @Override
    public ConfigParameter[] defineConfigParameters() {
        return new ConfigParameter[] { filterFilenameConfig, filterPollingInterval };
    }

    AvroInlet<Metric> inlet = AvroInlet.<Metric>create("in", Metric.class);
    AvroOutlet<Metric> outlet = AvroOutlet.<Metric>create("out", Metric.class);

    public StreamletShape shape() {
        return StreamletShape.createWithInlets(inlet).withOutlets(outlet);
    }

    private Boolean findDeviceIdInFilterFile(String deviceId, ArrayList<String> filterDeviceIds) {
        for (String current : filterDeviceIds) {
            if (current.equals(deviceId)) {
                return false;
            }
        }
        return true;
    }

    public AkkaStreamletLogic createLogic() {
        return new RunnableGraphStreamletLogic(getStreamletContext()) {
            final Config streamletConfig = getStreamletConfig();
            final Path referenceFilesPath = getMountedPath(referenceFiles);

            final Path filterFilenamePath = Paths.get(referenceFilesPath.toString(),
                    streamletConfig.getString(filterFilenameConfig.getKey()));

            final FiniteDuration pollingInterval = FiniteDuration
                    .create(streamletConfig.getInt(filterPollingInterval.getKey()), TimeUnit.SECONDS);

            final Source<ArrayList<String>, NotUsed> filterFileContent =
                DirectoryChangesSource
                .create(referenceFilesPath, pollingInterval, Integer.MAX_VALUE)
                .filter(changedFile ->
                    changedFile.second() != DirectoryChange.Deletion
                    &&
                    changedFile.first().equals(filterFilenamePath)
                )
                .map(Pair::first)
                .mapAsync(1, path ->
                    FileIO.fromPath(path)
                        .via(Framing.delimiter(ByteString.fromString("\n"), Integer.MAX_VALUE))
                        .runFold(
                            new ArrayList<String>(), (acc, entry) -> {
                                acc.addAll(Collections.singletonList(entry.utf8String()));
                                return acc;
                            },
                            getMaterializer()
                        )
                );

            public RunnableGraph createRunnableGraph() {
                return getPlainSource(inlet)
                    .via(Flow.create())
                    .zipLatest(filterFileContent)
                    .filter(filterFileAndMetric ->
                        findDeviceIdInFilterFile(
                            filterFileAndMetric.first().getDeviceId(),
                            filterFileAndMetric.second()
                        )
                    )
                    .map(Pair::first).to(getPlainSink(outlet));
            }
        };
    }
}

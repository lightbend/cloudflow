/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
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

package cloudflow.akkastreamsdoc;

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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class FilterStreamlet extends AkkaStreamlet {

    // Declare the volume mount
    private final VolumeMount referenceFiles = VolumeMount.createReadWriteMany("configuration", "/mnt/data");

    //tag::definition[]
    // Make the filter filename configurable
    private final StringConfigParameter filterFilenameConfig = StringConfigParameter.create(
                "filter-filename",
                "Name of the text file in the volume mount directory that contains the list of keys to filter out."
            ).withDefaultValue("device-ids.txt");
    //end::definition[]

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

    AvroInlet<Data> inlet = AvroInlet.<Data>create("in", Data.class);
    AvroOutlet<Data> outlet = AvroOutlet.<Data>create("out", Data.class);

    public StreamletShape shape() {
        return StreamletShape.createWithInlets(inlet).withOutlets(outlet);
    }

    private Boolean findDeviceIdInFilterFile(String key, ArrayList<String> filterKeys) {
        for (String current : filterKeys) {
            if (current.equals(key)) {
                return false;
            }
        }
        return true;
    }

    public AkkaStreamletLogic createLogic() {
        return new RunnableGraphStreamletLogic(getContext()) {
            final Config streamletConfig = getStreamletConfig();
            final Path referenceFilesPath = getMountedPath(referenceFiles);

            //tag::usage[]
            final Path filterFilenamePath = Paths.get(referenceFilesPath.toString(),
                    filterFilenameConfig.getValue(getContext()));
            //end::usage[]

            final Duration pollingInterval = java.time.Duration.ofSeconds(filterPollingInterval.getValue(getContext()));

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
                            system()
                        )
                );

            public RunnableGraph createRunnableGraph() {
                return getPlainSource(inlet)
                    .via(Flow.create())
                    .zipLatest(filterFileContent)
                    .filter(filterFileAndMetric ->
                        findDeviceIdInFilterFile(
                            filterFileAndMetric.first().getKey(),
                            filterFileAndMetric.second()
                        )
                    )
                    .map(Pair::first).to(getPlainSink(outlet));
            }
        };
    }
}

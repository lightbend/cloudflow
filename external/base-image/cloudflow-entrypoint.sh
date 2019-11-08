#!/usr/bin/env bash
#
# Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
CMD="$1"

JOB_MANAGER="jobmanager"
JOB_CLUSTER="jobcluster"
TASK_MANAGER="taskmanager"
I_AM_AKKA="akka"

if [[ "${CMD}" = "help" ]]; then
    echo "To invoke Flink entrypoint: $(basename $0) (${JOB_MANAGER}|${JOB_CLUSTER}|${TASK_MANAGER}|help)"
    echo "If the arguments are not among the options above, the Spark entrypoint will be invoked."
    exit 0
elif [[ "${CMD}" = "${JOB_MANAGER}" || "${CMD}" = "${JOB_CLUSTER}" || "${CMD}" = "${TASK_MANAGER}" ]]; then
    echo "Running Flink entrypoint script"
    /opt/flink-entrypoint.sh "$@"
elif [[ "${CMD}" = "${I_AM_AKKA}" ]]; then
    echo "Running Akka entrypoint script"
    /opt/akka-entrypoint.sh "$@"
else
    echo "Running Spark entrypoint script"
    /opt/spark-entrypoint.sh "$@"
fi

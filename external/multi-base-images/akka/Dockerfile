#
# Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
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

FROM adoptopenjdk/openjdk8

USER root

RUN mkdir -p /home/cloudflow && \
    mkdir -p /opt && \
    groupadd -r cloudflow -g 185 && \
    useradd -u 185 -r -g root -G cloudflow -m -d /home/cloudflow -s /sbin/nologin -c CloudflowUser cloudflow && \
    mkdir -p /prometheus && \
    curl  https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.11.0/jmx_prometheus_javaagent-0.11.0.jar -o /prometheus/jmx_prometheus_javaagent.jar 

COPY akka-entrypoint.sh /opt/
USER 185
ENTRYPOINT ["/opt/akka-entrypoint.sh"]

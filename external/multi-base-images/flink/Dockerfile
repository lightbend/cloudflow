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
FROM adoptopenjdk/openjdk8:alpine

# Prepare environment
ENV FLINK_VERSION=1.10.0 \
    SCALA_VERSION=2.12

ENV FLINK_HOME=/opt/flink

USER root

ENV FLINK_TGZ=lightbend-flink-$FLINK_VERSION.tgz
ENV FLINK_TGZ_URL=https://github.com/lightbend/flink/releases/download/v$FLINK_VERSION-lightbend/$FLINK_TGZ

RUN apk add curl wget bash snappy-dev

RUN mkdir ${FLINK_HOME} && \
    addgroup -S -g 9999 flink &&\
    adduser -S -h $FLINK_HOME -u 9999 flink flink && \
    addgroup -S -g 185 cloudflow && \
    adduser -u 185 -S -h /home/cloudflow -s /sbin/nologin cloudflow root && \
    adduser cloudflow cloudflow

# Note that gettext is for the envsubst command in the Flink entrypoint script.
RUN set -ex; \
  wget "$FLINK_TGZ_URL"; \
  tar -xvzf $FLINK_TGZ; \
  apk add gettext-dev; \
  rm -rf /var/lib/apt/lists/*; \
  rm $FLINK_TGZ; \
  mv flink-${FLINK_VERSION}/* $FLINK_HOME; \
  chown -R flink:flink /var; \
  chown -R flink:root /usr/local; \
  chmod 775 /usr/local; \
  mkdir $FLINK_HOME/flink-web-upload; \
  mv $FLINK_HOME/opt/flink-queryable-state-runtime_$SCALA_VERSION-$FLINK_VERSION.jar $FLINK_HOME/lib; \
  mkdir -p /prometheus && \
  curl https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.11.0/jmx_prometheus_javaagent-0.11.0.jar -o /prometheus/jmx_prometheus_javaagent.jar && \
  chmod -R 777 $FLINK_HOME

ADD config.sh $FLINK_HOME/bin/
ADD flink-console.sh $FLINK_HOME/bin/

RUN chmod 777 $FLINK_HOME/bin/flink-console.sh; \
    chown 501:dialout $FLINK_HOME/bin/flink-console.sh

# Update entrypoint
COPY flink-entrypoint.sh /opt/

ENV FLINK_HOME=/opt/flink
USER 185
ENTRYPOINT ["bash", "/opt/flink-entrypoint.sh"]

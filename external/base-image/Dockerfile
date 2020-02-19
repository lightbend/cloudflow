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
ARG SPARK_BASE_IMAGE=lightbend/spark:1.3.0-OpenJDK-2.4.5-cloudflow-2.12

FROM $SPARK_BASE_IMAGE

# Prepare environment
ENV FLINK_VERSION=1.10.0 \
    SPARK_VERSION=2.4.5 \
    SCALA_VERSION=2.12

ENV FLINK_HOME=/opt/flink

ADD spark-entrypoint.sh /opt/spark-entrypoint.sh

# Add the connector jar needed to access Google Cloud Storage using the Hadoop FileSystem API.
ADD https://storage.googleapis.com/hadoop-lib/gcs/gcs-connector-latest-hadoop2.jar $SPARK_HOME/jars

# Add Flink
USER root

ENV FLINK_TGZ=flink-${FLINK_VERSION}-bin-scala_${SCALA_VERSION}.tgz
ENV FLINK_URL_FILE_PATH=flink/flink-${FLINK_VERSION}/${FLINK_TGZ}
ENV FLINK_TGZ_URL=https://mirrors.ocf.berkeley.edu/apache/$FLINK_URL_FILE_PATH

RUN mkdir ${FLINK_HOME}; \
    groupadd --system --gid=9999 flink &&\
    useradd --system --home-dir $FLINK_HOME --uid=9999 --gid=flink flink; \
    mkdir -p /opt/spark/conf

COPY log4j.properties /opt/spark/conf

# Note that gettext is for the envsubst command in the Flink entrypoint script.
RUN set -ex; \
  curl -O "$FLINK_TGZ_URL"; \
  tar -xvzf $FLINK_TGZ; \
  apt-get -qq update; \
  apt-get -y -q install gettext; \
  rm $FLINK_TGZ; \
  mv flink-${FLINK_VERSION}/* $FLINK_HOME; \
  chown -R flink:flink .; \
  chown -R flink:flink /var; \
  chown -R flink:root /usr/local; \
  chmod 775 /usr/local; \
  mkdir /opt/cloudflow &&\
  mkdir $FLINK_HOME/flink-web-upload; \
  mv $FLINK_HOME/opt/flink-queryable-state-runtime_$SCALA_VERSION-$FLINK_VERSION.jar $FLINK_HOME/lib; \
  chmod -R 777 $FLINK_HOME &&\
  chmod -R 777 $SPARK_HOME

ADD config.sh $FLINK_HOME/bin/

# Update entrypoint
# Copy orchestrating entrypoint script to the image
COPY flink-entrypoint.sh cloudflow-entrypoint.sh /opt/

ENV FLINK_HOME=/opt/flink
ENV SPARK_HOME=/opt/spark
USER 185
ENTRYPOINT ["/opt/cloudflow-entrypoint.sh"]

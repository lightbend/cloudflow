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
FROM openjdk:8-jre

# Prepare environment
ENV FLINK_VERSION=1.9.2 \
    SCALA_VERSION=2.12

ENV FLINK_HOME=/opt/flink

USER root

ENV FLINK_TGZ=flink-${FLINK_VERSION}-bin-scala_${SCALA_VERSION}.tgz
ENV FLINK_URL_FILE_PATH=flink/flink-${FLINK_VERSION}/${FLINK_TGZ}
ENV FLINK_TGZ_URL=https://mirrors.ocf.berkeley.edu/apache/$FLINK_URL_FILE_PATH

RUN mkdir ${FLINK_HOME}; \
    groupadd --system --gid=9999 flink && \
    useradd --system --home-dir $FLINK_HOME --uid=9999 --gid=flink flink; \
    groupadd -r cloudflow -g 185 && \
    useradd -u 185 -r -g root -G cloudflow -m -d /home/cloudflow \
    -s /sbin/nologin -c CloudflowUser cloudflow

# Note that gettext is for the envsubst command in the Flink entrypoint script.
RUN set -ex; \
  curl -O "$FLINK_TGZ_URL"; \
  tar -xvzf $FLINK_TGZ; \
  apt-get update; \
  apt-get -y install libsnappy1v5 gettext-base; \
  rm -rf /var/lib/apt/lists/*; \
  rm $FLINK_TGZ; \
  mv flink-${FLINK_VERSION}/* $FLINK_HOME; \
  chown -R flink:flink /var; \
  chown -R flink:root /usr/local; \
  chmod 775 /usr/local; \
  mkdir $FLINK_HOME/flink-web-upload; \
  mv $FLINK_HOME/opt/flink-queryable-state-runtime_$SCALA_VERSION-$FLINK_VERSION.jar $FLINK_HOME/lib; \
  chmod -R 777 $FLINK_HOME

ADD config.sh $FLINK_HOME/bin/

# Update entrypoint
COPY flink-entrypoint.sh /opt/

ENV FLINK_HOME=/opt/flink
USER 185
ENTRYPOINT ["/opt/flink-entrypoint.sh"]

#!/usr/bin/env bash
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
SCRIPT=`basename ${BASH_SOURCE[0]}`
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
TAG=v2.4.5
ORIGIN_BRANCH=custom-2.4.5
DOCKER_USERNAME=lightbend
SPARK_IMAGE_TAG=1.3.0-OpenJDK-2.4.5-cloudflow-2.12
SPARK_OPERATOR_TAG=1.3.0-OpenJDK-2.4.5-1.1.0-cloudflow-2.12

set -ex
if [ "$(uname)" == "Darwin" ]; then
  export JAVA_HOME="$(dirname $(readlink $(which javac)))/java_home"
elif [ "$(expr substr $(uname -s) 1 5)" == "Linux" ]; then
  export JAVA_HOME="$(dirname $(dirname $(readlink -f $(which javac))))"
else
  echo "Please setup JAVA_HOME."
  exit 1
fi

rm -rf $DIR/spark
git clone https://github.com/lightbend/spark.git
cd $DIR/spark
git remote add upstream https://github.com/apache/spark.git
git fetch --tags --all
git checkout -b cloudflow-$ORIGIN_BRANCH origin/$ORIGIN_BRANCH

rm -rf resource-managers/kubernetes/lightbend-build
$DIR/spark/dev/change-scala-version.sh 2.12
$DIR/spark/dev/make-distribution.sh --name cloudflow-2.12 --r --tgz -Psparkr -Pscala-2.12 -Phadoop-2.7 -Pkubernetes -Phive
# remove upstream because hub will use it as the default push repo
git remote remove upstream

# build the Spark image
tar -zxvf spark-${TAG:1}-bin-cloudflow-2.12.tgz
cd $DIR/spark/spark-${TAG:1}-bin-cloudflow-2.12
cp $DIR/metrics.properties $DIR/spark/spark-${TAG:1}-bin-cloudflow-2.12
cp $DIR/prometheus.yaml $DIR/spark/spark-${TAG:1}-bin-cloudflow-2.12
docker build -f $DIR/Dockerfile -t $DOCKER_USERNAME/spark:$SPARK_IMAGE_TAG .

# build the Spark operator image
cd $DIR
rm -rf $DIR/spark-on-k8s-operator
git clone https://github.com/GoogleCloudPlatform/spark-on-k8s-operator.git
cd $DIR/spark-on-k8s-operator
git checkout f78361119976beb7a147df9cd64e1fdd317b9311 -b spark-operator-1.1.0
# adoptjdk image comes with all packages installed and also is based on ubuntu
sed -i -e '/RUN apk add --no-cache openssl curl tini/d' Dockerfile
docker build --no-cache --build-arg SPARK_IMAGE=$DOCKER_USERNAME/spark:$SPARK_IMAGE_TAG -t $DOCKER_USERNAME/sparkoperator:$SPARK_OPERATOR_TAG -f Dockerfile .

docker push $DOCKER_USERNAME/spark:$SPARK_IMAGE_TAG
docker push $DOCKER_USERNAME/sparkoperator:$SPARK_OPERATOR_TAG

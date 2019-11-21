#!/usr/bin/env bash

# Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
TARGET=$1

echo "========================================================================="
echo "Runs 'sbt $TARGET' for examples"
echo "========================================================================="

# Obtain current project list from examples.yaml
PROJECTS=$(cat $DIR/../examples.yaml | grep "path" | cut -d\" -f2)

for prj in $PROJECTS; do
  echo "========================================================================="
  echo "${TARGET}: $prj"
  echo "========================================================================="

  cd $DIR/../$prj
  sbt $TARGET
  RETVAL=$?
  [ $RETVAL -ne 0 ] && echo "Failure in project $prj" && exit -1
  cd ../..
done

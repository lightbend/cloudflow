#!/usr/bin/env bash

# Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
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

set -x

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
TARGET=$1
if [ "$TARGET" == "" ]; 
then
  set +x
  echo "==================================================================================="
  echo "Error: 'target' missing."
  echo "Usage: build-all.sh <target>  # where <target> is an sbt target: compile, test, ..."
  echo "==================================================================================="
  exit -1
fi
echo "========================================================================="
echo "Runs 'sbt $TARGET' for core and examples"
echo "========================================================================="

cd $DIR/../core
sbt --supershell=false "; scalafmtCheck ; $TARGET  ; publishLocal"
RETVAL=$?
[ $RETVAL -ne 0 ] && echo "Failure in building of core" && exit -1

echo "Core streamlet libraries built, tested and published to local"
echo "Now starting build of installer"

cd ../installer
sbt --supershell=false "; scalafmtCheck ; clean ; test"
RETVAL=$?
[ $RETVAL -ne 0 ] && echo "Failure in building of installer" && exit -1

echo "Installer built and tested, docker image built"
echo "Now starting building of examples..."

# Following section has been commented - will uncomment when we have a way
# to publish artifacts since we need to specify the plugin version for each example

cd ../examples

# Obtain current project list from examples.yaml
PROJECTS=$(cat $DIR/../examples/examples.yaml | grep "path" | cut -d\" -f2)

for prj in $PROJECTS; do
  echo "========================================================================="
  echo "${TARGET}: $prj"
  echo "========================================================================="

  cd $prj
  case "$prj" in
    *-java)
      sbt --supershell=false "; $TARGET ; verifyBlueprint "
      ;;
    *)
      sbt --supershell=false "; scalafmtCheck ; $TARGET ; verifyBlueprint "
      ;;
  esac
  RETVAL=$?
  [ $RETVAL -ne 0 ] && echo "Failure in project $prj" && exit -1
  cd -
done

#!/usr/bin/env bash

# Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
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

SCRIPTS_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
ROOT_DIR=$(dirname "$SCRIPTS_DIR")

source "$SCRIPTS_DIR"/functions.sh

TARGET="${1:-test}"
check_argument "$1" "build-core.sh <target> # where <target> is a sbt task: compile, publishM2, ..."

show_message "Runs 'sbt $TARGET' for core"

cd $ROOT_DIR/core

sbt -mem 4096 --supershell=false "; scalafmtCheckAll ; $TARGET"
RETVAL=$?
[ $RETVAL -ne 0 ] && echo "Failure in building of core" && exit 1

show_message "Successfully ran $TARGET for Core streamlet libraries"

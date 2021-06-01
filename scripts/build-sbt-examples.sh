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
check_argument "$1" "build-sbt-examples.sh <target> # where <target> is a sbt task: compile, test, ..."

CLOUDFLOW_VERSION=$(echo_cf_version)
show_message "Cloudflow version is $CLOUDFLOW_VERSION"
export CLOUDFLOW_VERSION

# Publish a local version that will be used by the examples. This shouldn't change
# any files, so it is safe to run after reading the version.
"$SCRIPTS_DIR/build-core.sh" +publishLocal

# Find all directories under `examples` with a `build.sbt` file
PROJECTS=$(find "$ROOT_DIR/examples" -name build.sbt -exec dirname {} \;)

for prj in $PROJECTS; do
  show_message "sbt ${TARGET}: $prj"
  cd "$prj"

  # Only run `verifyBlueprint` task if there are blueprint files in the project
  blueprintTask=""
  if [[ -n $(find . -name "blueprint.conf") ]]; then
    blueprintTask="verifyBlueprint"
  fi

  case "$prj" in
    *-java)
      if ! sbt -mem 4096 --supershell=false "; $TARGET; $blueprintTask"; then
        show_message "Failed to run $TARGET and other checks for $prj"
        exit 1
      fi
      ;;
    *)
      if ! sbt -mem 4096 --supershell=false "; scalafmtCheckAll ; $TARGET; $blueprintTask"; then
        show_message "Failed to run $TARGET and other checks for $prj"
        exit 1
      fi
      ;;
  esac
done

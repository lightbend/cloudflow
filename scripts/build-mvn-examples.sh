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
check_argument "$1" "build-mvn-examples.sh <target> # where <target> is a mvn task: compile, test, ..."

CLOUDFLOW_VERSION=$(echo_cf_version)
show_message "Cloudflow version is $CLOUDFLOW_VERSION"
export CLOUDFLOW_VERSION

# Publish a local version that will be used by the examples. This shouldn't change
# any files, so it is safe to run after reading the version.
"$SCRIPTS_DIR/build-core.sh" +publishM2

# Find all directories under `examples` with a `pom.xml` file.
# `sort` so that the parent directories are shown on top.
PROJECTS=$(find "$ROOT_DIR/examples" -name pom.xml -exec dirname {} \; | sort)

# Avoid to execute the target for subprojects, which also have a `pom.xml` file
PARENT_PROJECTS=()
current_dir="not-a-project"
for item in $PROJECTS; do
    if [[ "${item}" == "${current_dir}"* ]]; then
        echo "$item is under $current_dir, filtering out"
    else
        current_dir="$item"
        PARENT_PROJECTS+=("${current_dir}")
    fi
done

for prj in "${PARENT_PROJECTS[@]}"; do
  show_message "mvn ${TARGET}: $prj"
  cd "$prj"

  # Only run `cloudflow:verify-blueprint` task if there are blueprint files in the project
  if [[ -n $(find . -name "blueprint.conf") ]]; then
    # For more details about Maven workflow, see the docs:
    # https://developer.lightbend.com/docs/cloudflow/shared-content/2.1.2/develop/maven-support.html
    if ! mvn package cloudflow:extract-streamlets -DskipTests; then
      show_message "Failed to extract streamlet for project $prj"
      exit 1
    fi

    if ! mvn cloudflow:verify-blueprint; then
      show_message "Failed to verify blueprint for project $prj"
      exit 1
    fi
  fi

  if ! mvn "$TARGET"; then
    show_message "Failed to run $TARGET for project $prj"
    exit 1
  fi
done

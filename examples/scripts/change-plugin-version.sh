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

VERSION=$1

if [[ -z "$VERSION" ]]; then
  echo "Usage: change-plugin-version.sh <version>"
  echo "Upgrades the cloudflow-sbt version of all projects to the provided version"
  exit -1
else
  echo Upgrading all projects to cloudflow-streamlets version [$VERSION]
fi

function current_version() {
  local searchKeyword=$1
  local target=$2
  cat $target | grep $searchKeyword | cut -d\" -f6
}

# Given a filename containing an addSbtPlugin command,
# it obtains the version of the plugin used
function current_plugin_version() {
  current_version "addSbtPlugin" $1
}

# Upgrade the version of the plugin for all projects
function upgrade_plugins() {
  local files=$(find . -name "cloudflow-plugins.sbt")
  for file in $files; do
    local current=$(current_plugin_version $file)

    echo "Upgrading plugin $file from version [$current] to [$VERSION]"

    if [[ "$OSTYPE" == "darwin"* ]]; then
      sed -i "" "s/$current/$VERSION/g" $file
    else
      sed -i -e "s/$current/$VERSION/g" $file
    fi
  done
}

# execute the upgrade
upgrade_plugins

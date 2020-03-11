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

export TILLER_SERVICE_ACCOUNT="tiller"
export TILLER_NAMESPACE="kube-system"

# Utils
. common/utils.sh

# Utility functions to interact with Tiller
. common/helm2.sh

# Exports the timeout that should be for executing Helm 3 commands
export_helm3_timeout() {
	HELM_TIMEOUT="600s"
	export HELM_TIMEOUT
}

# Detect which version of Helm is installed
detect_helm_version() {
  if [[ $(helm version | awk -F '[".]' '/version.Version/ { print $3 }' | sed -n '1p') == "v2" ]]
  then
    echo "Detected Helm version 2"
    HELM_VERSION="2"
  elif [[ $(helm version | awk -F '[".]' '/version.BuildInfo/ { print $3 }') == "v3" ]]
  then
    echo "Detected Helm version 3"

    HELM_VERSION="3"
  else
    print_error_message "Helm not found. Please install Helm before proceeding."
    exit 1
  fi

  export HELM_VERSION
}

# Test Helm3 installation
test_helm3_installation() {
  echo "Testing Helm 3 installation"

	helm delete test-helm3 --purge &> /dev/null

result=$(helm upgrade test-helm3 helm-test-chart --install \
--namespace "$1" \
--timeout $HELM_TIMEOUT)

	if [ $? -ne 0 ]; then 
	echo ""
	echo "$result"  
	echo ""
	echo "---------------------------------------------------------------------------------------------------"
	echo ""
	echo "The Helm test failed. In order to install Cloudflow, Helm must be able to modify the namespace '$1'."
	echo ""
	return 1
	else
		helm delete test-helm3 --purge &> /dev/null
		echo "Helm 3 is correctly configured"
		return 0
	fi	
}

# Initialise either Helm 2 or 3
init_helm() {
  detect_helm_version

  if [[ $HELM_VERSION == "2" ]]
  then
    export_helm2_timeout

    init_helm2
    verify_tiller_installation
    test_tiller_installation
  elif [[ $HELM_VERSION == "3" ]]
  then
    export_helm3_timeout
    
    test_helm3_installation
  fi
}

# Delete chart in either Helm 2 or 3
helm_delete() {
  if [ -z "$1" ]
    then
      print_error_message "Please provide a chart name to delete"
      exit 1
  fi

  if [[ $HELM_VERSION == "2" ]]
  then
    helm delete $1 --purge --no-hooks
  elif [[ $HELM_VERSION == "3" ]]
  then
    helm delete $1 --no-hooks -n cloudflow
  fi
}

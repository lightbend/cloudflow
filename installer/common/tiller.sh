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


# Returns the namespace where tiller is running or where it should be installed, by default we install it in kube-system
export_tiller_namespace() {
    echo "Checking for tiller"
	tillerNamespace=$(kubectl get pods -l name=tiller --all-namespaces --ignore-not-found=true --no-headers | awk -v i=1 -v j=1 'FNR == i {print $j}')
	if [ -z "$tillerNamespace" ]; then 
		return 1
	fi 
    TILLER_NAMESPACE="$tillerNamespace"
    export TILLER_NAMESPACE
	return 0
}

# Tests that Tiller is correctly installed by installing a chart with an Alpine pod 
# Args: namespace
test_tiller() {
	echo "The tiller namespace is '$TILLER_NAMESPACE'"

	helm delete test-tiller --purge &> /dev/null

result=$(helm upgrade test-tiller tiller-test-chart --install \
--namespace "$1" \
--timeout 600)

	if [ $? -ne 0 ]; then 
	echo ""
	echo "$result"  
	echo ""
	echo "---------------------------------------------------------------------------------------------------"
	echo ""
	echo "The Tiller test failed. In order to install Cloudflow, Tiller must be able to modify the namespace '$1'."
	echo "More information about configuring Tiller can be found here: https://github.com/helm/helm/blob/master/docs/rbac.md"
	echo ""
	return 1
	else
		helm delete test-tiller --purge &> /dev/null
		echo "Tiller is correctly configured"
		return 0
	fi	
}

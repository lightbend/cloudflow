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

# Init Helm 2
init_helm2() {
  export_tiller_namespace
  if [ $? -ne 0 ]; then
		echo "Tiller not found. Installing Tiller."

		namespaceExists=$(detect_k8s_object "kubectl get ns $TILLER_NAMESPACE")
		if [ "$namespaceExists" == "0" ]; then
			kubectl create ns $TILLER_NAMESPACE
		fi

		kubectl create serviceaccount --namespace "$TILLER_NAMESPACE" "$TILLER_SERVICE_ACCOUNT"
		kubectl create clusterrolebinding "$TILLER_NAMESPACE":tiller --clusterrole=cluster-admin --serviceaccount="$TILLER_NAMESPACE":"$TILLER_SERVICE_ACCOUNT"
		helm init --wait --service-account "$TILLER_SERVICE_ACCOUNT" --upgrade --tiller-namespace="$TILLER_NAMESPACE"
  else
		echo "Tiller found, the version of Tiller will be synchronized with the local Helm version, this may downgrade Tiller."
		read -p "Do you want to continue? (y/n) " -n 1 -r
		echo
		if [[ $REPLY =~ ^[Yy]$ ]]; then
				# Upgrade Tiller so versions match
				helm init --upgrade --force-upgrade 2>&1 > /dev/null
		else
				print_error_message "Installation cancelled."
				exit 1
		fi
  fi
}

# Verify that Tiller is deployed successfully
verify_tiller_installation() {
  kubectl rollout status deployment/tiller-deploy  -n "$TILLER_NAMESPACE"
  if [ $? -ne 0 ]; then
		print_error_message "Tiller failed to deploy. Please correct the problem and re-run the installer."
		exit 1
  fi
}

# Test Tiller on the cluster
test_tiller_installation() {
  echo "Testing Tiller"
  test_tiller "$CLOUDFLOW_NAMESPACE"
  if [ $? -ne 0 ]; then
		exit 1
  fi
}

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

# Exports the timeout that should be for executing Helm commands
export_helm2_timeout() {
	HELM_TIMEOUT="600"
	export HELM_TIMEOUT
}

# Tests that Tiller is correctly installed by installing a chart with an Alpine pod 
# Args: namespace
test_tiller() {
	echo "The tiller namespace is '$TILLER_NAMESPACE'"

	helm delete test-tiller --purge &> /dev/null

result=$(helm upgrade test-tiller helm-test-chart --install \
--namespace "$1" \
--timeout $HELM_TIMEOUT)

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

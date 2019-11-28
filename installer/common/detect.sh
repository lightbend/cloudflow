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

# Returns the namespace strimzi is installed in or an empty string
detect_strimzi() {
    kubectl get deployment -l app=strimzi --all-namespaces --ignore-not-found=true --no-headers | awk -v i=1 -v j=1 'FNR == i {print $j}'
}

# Checks if the Strimzi CRDs that we depend on is installed
detect_strimzi_crds() {
	kubectl get crd kafkas.kafka.strimzi.io kafkatopics.kafka.strimzi.io > /dev/null 2>&1
}

# Returns the namespace the sparkoperator is installed in or an empty string
detect_sparkoperator() {
    kubectl get deployment -l app.kubernetes.io/name=fdp-sparkoperator --all-namespaces --ignore-not-found=true --no-headers | awk -v i=1 -v j=1 'FNR == i {print $j}'
}

# Returns the namespace where the Flink operator is installed or an empty string
detect_flinkoperator() {
	kubectl get deployment -l app.kubernetes.io/name=flink-operator --all-namespaces --ignore-not-found=true --no-headers | awk -v i=1 -v j=1 'FNR == i {print $j}'
}

# Returns true if the Flink operator is configured to watch all namespaces
detect_flink_watch_all_namespaces() {
	# Currently awaiting a fix to make the operator config easier to parser in Bash.
	#local result=$(kubectl get configmap -n lightbend flink-operator-config -o json --ignore-not-found=true | jq '.data.config.limitNameSpace')
	#if [ -z result ]; then
	#	result=true
	#else
	#	result=false
	#fi
	echo true
}

# Returns the namespace the Kafka lag exporter is installed in or an empty string
detect_kafkalagexporter() {
    kubectl get pods --all-namespaces -l app.kubernetes.io/name=kafka-lag-exporter --ignore-not-found=true --no-headers | awk -v i=1 -v j=1 'FNR == i {print $j}'
}

# Returns a zero if kafka has not been deployed into this namespace
detect_kafka_cluster() {
	kubectl get deployment -l strimzi.io/kind=Kafka --ignore-not-found=true -n "$1" --no-headers | wc -l
}

# Returns a zero if the mutating webhook has not been enabled
detect_mutating_webhook() {
	kubectl api-resources | grep MutatingWebhookConfiguration | wc -l
}

# Returns count of objects found from a `kubectl get`
detect_k8s_object() {
  cmd=$1
  $cmd --ignore-not-found=true --no-headers | wc -l 
}

# Returns a string of the os the script is executing on
detect_os_type() {
	# Detect OS
	unameOut="$(uname -s)"
	case "${unameOut}" in
    	Linux*)     osType=linux;;
    	Darwin*)    osType=osx;;
    	CYGWIN*)    osType=cygwin;;
    	MINGW*)     osType=mingw;;
    	*)          osType="UNKNOWN:${unameOut}"
	esac
	echo "$osType"
}

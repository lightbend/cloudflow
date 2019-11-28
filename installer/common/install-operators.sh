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


# Install Operator-based projects and their CRD's as requirements to Cloudflow
#
# This is a workaround due to a bug in Helm v2 which doesn't correctly resolve
# CustomResourceDefinition dependencies.  CRD's defined by operator projects
# must be installed before being referenced by a chart.
#
# Ex) Strimzi creates a `kafkas.kafka.strimzi.io` CRD which is used to define
# a Kafka cluster to install.
#
# More information about the issue and workarounds
#
# * Helm Documentation: Install CRD before using the resource
#   https://docs.helm.sh/chart_best_practices/#install-a-crd-declaration-before-using-the-resource
# * Central issue to track this bug:
#   https://github.com/helm/helm/issues/2994)

# Flink
if [ "$installFlinkOperator" = true ]; then
    echo "Installing Flink in $flinkOperatorNamespace"
    result=$(helm upgrade "$flinkReleaseName" \
    --install \
    --namespace "$flinkOperatorNamespace" \
    --version "$flinkOperatorChartVersion" \
    --set serviceAccounts.flink.create=false \
    --set serviceAccounts.flink.name=cloudflow-app-serviceaccount \
    lightbend-helm-charts/flink-operator)

    if [ $? -ne 0 ]; then 
        print_error_message "$result"
        print_error_message "installation failed"
        exit 1
    fi

    # Label the Flink operator deployment so we can detect if we installed it.
    kubectl label deployment -n "$flinkOperatorNamespace" cloudflow-flink-flink-operator installed-by=cloudflow --overwrite
fi

# Strimzi 
if [ "$installStrimzi" = true ]; then
    echo "Installing Strimzi"
    result=$(helm upgrade "$strimziReleaseName" \
    --install \
    --namespace "$strimziClusterOperatorNamespace" \
    --version "$strimziVersion" \
    strimzi/strimzi-kafka-operator)

    if [ $? -ne 0 ]; then 
        print_error_message "$result"
        print_error_message "installation failed"
        exit 1
    fi

    # Label the cluster operator deployment so we can detect if we installed it later
    kubectl label deployment -n "$strimziClusterOperatorNamespace" strimzi-cluster-operator installed-by=cloudflow --overwrite
fi

# Spark Operator
if [ "$installSparkOperator" = true ]; then
    echo "Installing Spark operator"
    result=$(helm upgrade "$sparkOperatorReleaseName" \
    --install \
    --namespace "$sparkOperatorNamespace" \
    --values="$currentDirectory"/spark-operator-values.yaml \
    --version "$sparkOperatorChartVersion" \
    --set sparkJobNamespace="" \
    --set operatorVersion="$sparkOperatorImageVersion" \
    lightbend-helm-charts/fdp-sparkoperator)

    if [ $? -ne 0 ]; then 
        print_error_message "$result"
        print_error_message "installation failed"
        exit 1
    fi

    # Label the deployment so we can detect if we installed it later
    kubectl label deployment -n "$sparkOperatorNamespace" "$sparkOperatorReleaseName"-fdp-sparkoperator installed-by=cloudflow --overwrite
fi

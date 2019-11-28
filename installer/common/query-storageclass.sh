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

# The purpose of this script is to help the user installing Cloudflow
# selecting storage classes for different workloads.
#
# The script has a hardcoded non-exhaustive list of storage class provisioners
# with known capabilities, the list is used to indicate storage classes "known to work"
# for specific workloads.
#
# The script requires bash 4.x and higher
#
# Exports the following variables:
#
# export selectedRWMStorageClass
# export selectedRWOStorageClass

# Check that 'jq' is installed 

# shellcheck source=common/utils.sh
. $(dirname "$0")/utils.sh

# The following commands extracts all storage classes with their provisioner and if the storage class is the default one.
kubectlOutput=$(kubectl get sc -o json | jq -r '.items[] | {name:.metadata.name, provisionor:.provisioner, default:.metadata.annotations."storageclass.beta.kubernetes.io/is-default-class"}  | join("\t ")')

# A map where `key` is storage class and `value` is provisioner
declare -A clusterStorageClassProvisioner=(); while read -r a b c; do clusterStorageClassProvisioner["$a"]="$b"; done <<< $kubectlOutput
if [ $? -ne 0 ]; then
    print_error_message "The installer has failed to connect to the cluster; please check your connect and try again."
    exit 1
fi
# Extract the default storage class (if any)
defaultStorageClass=$(echo "$kubectlOutput" | awk '$3 == "true"' | awk '{print $1}')
# Make sure the map contains some entries
if [ ${#clusterStorageClassProvisioner[@]} == 0 ]; then
    print_error_message "Could not retrieve a list of registered storage classes from the cluster."
    print_error_message "This may indicate that the cluster currently has no configured storage classes." 
    print_error_message "Cloudflow needs to create Persistent Volumes (PV) for components" 
    print_error_message "like Kafka and Spark to work. PVs require you to register one or more storage classes."
    print_error_message "For more information, please see the Cloudflow installation guide."
    exit 1
fi
# Create a list of keys from the map
clusterStorageClassKeys=(${!clusterStorageClassProvisioner[@]})

# List of verified RWM provisioners
declare -a readWriteManyProvisioners=(
    "kubernetes.io/glusterfs"
    "kubernetes.io/azure-file"
    "kubernetes.io/quobyte"
    "kubernetes.io/nfs"
)
# List of verified RWO provisioners
declare -a readWriteOnceProvisioners=(
    "kubernetes.io/aws-ebs"
    "kubernetes.io/azure-file"
    "kubernetes.io/azure-disk"
    "kubernetes.io/gce-pd"
    "kubernetes.io/glusterfs"
    "kubernetes.io/rbd"
    "kubernetes.io/quobyte"
    "kubernetes.io/nfs"
    "kubernetes.io/vsphere-volume"
    "kubernetes.io/scaleio"
    "kubernetes.io/storageos"
    "kubernetes.io/portworx-volume"
)

# The function takes one parameter, and that is the number of
# entries in the list that the user selects from.
#
# $1 - The number of entries to select from
#
# The function returns the index of the selected class.
queryStorageClass() {
    local lowerBound=1
    local upperBound=$1

    selectedClassIndex=""
    returnValue=""
    # Keep asking until we get something
    while [[ -z "$returnValue" ]]
    do
        read -p "> " selectedClassIndex
        if [[ $selectedClassIndex -ge $lowerBound && $selectedClassIndex -le $upperBound ]]; then
            returnValue=$selectedClassIndex
        else
            returnValue="-1"
        fi
    done
    echo $returnValue
}

# Prints detected storage classes and our classification of them for this type of access mode.
#
# $1 - The list of provisioners capable of creating volumes with the requested access mode.
#
# The function does not return anything.
printSelection() {
    local provisonerList=("$@")
    count=1
    printf "   %-20s %-30s %-30s%-30s\n" "NAME" "PROVISIONER" "SUPPORTS RW?" "DEFAULT?"
    for i in "${!clusterStorageClassProvisioner[@]}"
    do
        # Check if provisioner is in list
        local found=false
        for element in "${provisonerList[@]}"; do
            if [[ $element == ${clusterStorageClassProvisioner[$i]} ]]; then
                found=true
            fi
        done

        if [ "$found" == true ]
        then 
            printf "%s. %-20s %-30s %-30s" $count $i ${clusterStorageClassProvisioner[$i]} "Verified"
        else
            printf "%s. %-20s %-30s %-30s" $count $i ${clusterStorageClassProvisioner[$i]} "Unknown"
        fi
        if [ "$i" == "$defaultStorageClass" ]
        then
            printf "YES\n"
        else 
            printf "%s\n" "-"        
        fi
        ((count++))
    done
}

echo ""
echo "Select a storage class for workloads requiring persistent volumes with access mode 'ReadWriteMany'."
echo "Examples of these are Spark and Flink checkpointing and savepointing."
echo ""
printSelection "${readWriteManyProvisioners[@]}"

selectedIndex=-1
while [[ "$selectedIndex" == -1 ]]
do
    selectedIndex=$(queryStorageClass ${#clusterStorageClassProvisioner[@]})
    if [[ $selectedIndex == -1 ]]; then 
        print_error_message "The value you selected is not a valid choice."
    fi
done
export selectedRWMStorageClass=${clusterStorageClassKeys[(($selectedIndex-1))]}

echo ""
echo "Select a storage class for workloads requiring persistent volumes with access mode 'ReadWriteOnce'."
echo "Examples of these are Kafka, Zookeeper, and Prometheus."
echo ""
printSelection "${readWriteOnceProvisioners[@]}"

selectedIndex=-1
while [[ "$selectedIndex" == -1 ]]
do
    selectedIndex=$(queryStorageClass ${#clusterStorageClassProvisioner[@]})
    if [[ $selectedIndex == -1 ]]; then 
        print_error_message "The value you selected is not a valid choice."
    fi
done
export selectedRWOStorageClass=${clusterStorageClassKeys[(($selectedIndex-1))]}

echo ""
echo "- Using storage class '$selectedRWMStorageClass' for workloads requiring persistent volumes with 'ReadWriteMany' access mode."
echo "- Using storage class '$selectedRWOStorageClass' for workloads requiring persistent volumes with 'ReadWriteOnce' access mode."
echo ""

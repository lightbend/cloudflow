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

# Usage:
# create-cluster-eks.sh [CLUSTER_NAME] [AWS_DEFAULT_REGION]
if [ $# -ne 2 ]; then
  echo "Not enough arguments supplied"
  echo "Usage: create-cluster-eks.sh [CLUSTER_NAME] [AWS_DEFAULT_REGION]"
  exit 1
fi

CLUSTER_NAME=$1
AWS_DEFAULT_REGION=$2

# Find the first 3 available zones
AWS_REGION_NO_HYPHENS="$(echo "$AWS_DEFAULT_REGION" | sed -e 's/\-//g' | tr '[:lower:]' '[:upper:]')"

ZONE_1="$(aws ec2 describe-availability-zones \
  --filters Name=region-name,Values="$AWS_DEFAULT_REGION" | jq -r ".AvailabilityZones | .[].ZoneName" | sed -n '1p' | grep -o .'\{1\}$')"

ZONE_2="$(aws ec2 describe-availability-zones \
  --filters Name=region-name,Values="$AWS_DEFAULT_REGION" | jq -r ".AvailabilityZones | .[].ZoneName" | sed -n '2p' | grep -o .'\{1\}$')"

ZONE_3="$(aws ec2 describe-availability-zones \
  --filters Name=region-name,Values="$AWS_DEFAULT_REGION" | jq -r ".AvailabilityZones | .[].ZoneName" | sed -n '3p' | grep -o .'\{1\}$')"

# Create cluster
# https://docs.aws.amazon.com/eks/latest/userguide/create-cluster.html
eksctl create cluster \
  --name "$CLUSTER_NAME" \
  --version 1.14 \
  --region "$AWS_DEFAULT_REGION" \
  --nodegroup-name standard-workers \
  --node-type t3.xlarge \
  --nodes 3 \
  --nodes-min 1 \
  --nodes-max 7 \
  --managed \
  --zones="$AWS_DEFAULT_REGION$ZONE_1,$AWS_DEFAULT_REGION$ZONE_2,$AWS_DEFAULT_REGION$ZONE_3"

# Attach EFS policy to Cloudflow roles
cat <<EOF >describe-file-systems-policy.json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "VisualEditor0",
      "Effect": "Allow",
      "Action": "elasticfilesystem:DescribeFileSystems",
      "Resource": "*"
    }
  ]
}
EOF

ROLE_NAME_STANDARD=$(aws eks describe-nodegroup --nodegroup-name "$(aws eks list-nodegroups --cluster-name "$CLUSTER_NAME" | jq -r '.nodegroups[]' | sed -n '1p')" --cluster-name "$CLUSTER_NAME" | jq -r '.nodegroup.nodeRole')
echo "Role name workers: ${ROLE_NAME_STANDARD##*/}"

# Ignore the error if the policy was already created during a previous run
aws iam create-policy \
  --policy-name cloudlfow-describe-file-systems \
  --policy-document file://describe-file-systems-policy.json > /dev/null 2>&1

POLICY_ARN=$(aws iam list-policies | jq -r '.Policies[] | select(.PolicyName=="cloudlfow-describe-file-systems") | .Arn')

aws iam attach-role-policy \
  --policy-arn "${POLICY_ARN}" \
  --role-name "${ROLE_NAME_STANDARD##*/}"

rm describe-file-systems-policy.json

# Create EFS
aws efs create-file-system \
  --creation-token "$CLUSTER_NAME" \
  --performance-mode generalPurpose \
  --throughput-mode bursting \
  --region "$AWS_DEFAULT_REGION" \
  --tags Key=Name,Value="$CLUSTER_NAME"

sleep 10

FILE_SYSTEM_ID="$(aws efs describe-file-systems --query "FileSystems[?Name=='$CLUSTER_NAME'].FileSystemId" --output json | jq -r '.[]')"
echo "File system id: $FILE_SYSTEM_ID"

SECURITY_GROUP_IDS="$(aws eks describe-cluster --name "$CLUSTER_NAME" | jq -r '.cluster.resourcesVpcConfig.securityGroupIds | .[0]')"
echo "Security group id's: $SECURITY_GROUP_IDS"

CLUSTER_SECURITY_GROUP_ID="$(aws eks describe-cluster --name "$CLUSTER_NAME" | jq -r '.cluster.resourcesVpcConfig.clusterSecurityGroupId')"
CLUSTER_SECURITY_GROUP_ID_TEST_NULL="${CLUSTER_SECURITY_GROUP_ID/#null/}"

if [ "${#CLUSTER_SECURITY_GROUP_ID_TEST_NULL}" -eq "0" ]; then
  CLUSTER_SECURITY_GROUP_ID=""
fi

echo "Cluster security group id: ${CLUSTER_SECURITY_GROUP_ID:-'not found'}"

# Mount EFS targets (one for each zone)
for zone_value in $ZONE_1 $ZONE_2 $ZONE_3; do

  ZONE="$(echo "$zone_value" | tr '[:lower:]' '[:upper:]')"
  echo "Zone: $ZONE"

  SUBNET_ID="$(aws ec2 describe-subnets --filters Name=tag:Name,Values="eksctl-$CLUSTER_NAME-cluster/SubnetPublic$AWS_REGION_NO_HYPHENS$ZONE" --output json | jq -r '.Subnets | .[].SubnetId')"
  echo "Subnet id: $SUBNET_ID"

  # shellcheck disable=SC2086
  aws efs create-mount-target \
    --file-system-id "$FILE_SYSTEM_ID" \
    --subnet-id "$SUBNET_ID" \
    --security-groups "$SECURITY_GROUP_IDS" ${CLUSTER_SECURITY_GROUP_ID:-}
done

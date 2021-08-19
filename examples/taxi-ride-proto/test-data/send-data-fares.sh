#!/usr/bin/env bash
#
# Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

while getopts ":p:" opt; do
  case $opt in
    p) 
      port="$OPTARG"
      ;;
    \?) 
      echo "Invalid option -$OPTARG" >&2
      exit 1
      ;;
    :) 
      echo "Option -$OPTARG requires an argument." >&2
      exit 1
      ;;
  esac
done

if [[ -z "$port" ]]; then
  echo "Usage: send-data-fares.sh -p <port>"
  exit 1
fi

for str in $(cat nycTaxiFares.json)
do
  echo "Using $str"
  curl -i -X POST http://localhost:"$port" -H "Content-Type: application/json" --data "$str"
done

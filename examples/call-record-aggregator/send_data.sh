#!/bin/bash

DEFAULT_DATASET="./datamodel/data/data-sample-20.json"
if [ "$1" == "" ]
then
  RESOURCE=$DEFAULT_DATASET
else
  RESOURCE="$1"
fi  

echo "Using $RESOURCE"

ROUTE_HOST=$(kubectl cloudflow status call-record-pipeline | grep /cdr-ingress | awk '{print $2}')

for str in $( cat $RESOURCE ); do
  echo Sending $str
  curl -i \
  -X POST $ROUTE_HOST \
  -u assassin:4554551n \
  -H "Content-Type: application/json" \
  --data "$str"
done  

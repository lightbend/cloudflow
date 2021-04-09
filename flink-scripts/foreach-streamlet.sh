#! /bin/bash

APPLICATION=$1
if [ -z "$APPLICATION" ]; then
    echo "No application name specified."
    exit 1
fi
COMMAND=$2
if [ -z "$COMMAND" ]; then
    echo "No command specified."
    exit 1
fi

for streamlet_folder in .tmp/${APPLICATION}/*/; do
  eval "$COMMAND $streamlet_folder ${APPLICATION} ${@:3}"
done

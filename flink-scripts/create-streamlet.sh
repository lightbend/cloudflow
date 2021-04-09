#! /bin/bash

STREAMLET_FOLDER=$1
if [ -z "$STREAMLET_FOLDER" ]; then
    echo "No streamlet folder specified."
    exit 1
fi

(
  cd "$STREAMLET_FOLDER"
  ./output/cli-cmd.sh
)

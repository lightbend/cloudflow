#!/usr/bin/env bash

SCRIPTS_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
ROOT_DIR=$(dirname "$SCRIPTS_DIR")

# Generates a stable version that will be used instead of reading 
# from dynver. This is useful when running tasks that can change the
# code for all the samples
function echo_cf_version() {
    cd "$ROOT_DIR/core"
    VERSION=$(sbt --supershell=false "show version" | tail -n 1 | cut -d' ' -f 2 | tr -d '[:space:]')
    echo "$VERSION"
}

function show_message() {
    message="$1"
    echo "-------------------------------------------------------------------------"
    echo "${message}"
    echo "-------------------------------------------------------------------------"
}

function check_argument() {
    target=$1
    message=$2
    if [ -z "$target" ];
    then
        echo "==================================================================================="
        echo "Using 'test' as the default target. If you want to run another target, run:"
        echo "  $message"
        echo "==================================================================================="
    fi
}
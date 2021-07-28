#!/usr/bin/env bash

SCRIPTS_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
ROOT_DIR=$(dirname "$SCRIPTS_DIR")

function echo_cf_version() {
    cd "$ROOT_DIR/core"
    # scoping to cloudflow-akka because we only want to get the version once.
    sbt --supershell=false --no-colors --error "print cloudflow-akka/version"
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
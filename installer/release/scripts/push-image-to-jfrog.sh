#!/bin/bash
TAG=$1
VERSION=$2

JFROG_TAG=lightbendcloudflow-docker-local.jfrog.io/${TAG//./-}:$VERSION

docker pull $TAG:$VERSION
docker tag $TAG:$VERSION $JFROG_TAG
docker push $JFROG_TAG

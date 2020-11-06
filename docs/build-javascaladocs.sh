#! /bin/bash

VERSION=$1

echo $VERSION

if [[ -z "${VERSION}" ]]; then
  echo "Version not defined"
else
  (cd target && \
    rm -rf cloudflow && \
    git clone --depth=100 --branch "$VERSION" https://github.com/lightbend/cloudflow.git && \
    cd cloudflow && \
    export DIR=$(yq r docs/docs-source/docs/antora.yml 'version') && \
    echo $DIR && \
    cd core && \
    (sbt -mem 2048 clean unidoc || true) && \
    cd ../../../ && \
    ls -al "./target/cloudflow/core/target" && \
    ls -al "./target/cloudflow/core/target/scala-2.12" && \
    mkdir -p "./target/staging/docs/$DIR/api/scaladoc" && \
    mkdir -p "./target/staging/docs/$DIR/api/javadoc" && \
    cp -r "./target/cloudflow/core/target/scala-2.12/unidoc/" "./target/staging/docs/$DIR/api/scaladoc" && \
    cp -r "./target/cloudflow/core/target/javaunidoc/" "./target/staging/docs/$DIR/api/javadoc")
fi

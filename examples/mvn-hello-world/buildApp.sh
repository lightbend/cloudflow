#! /bin/bash

PROJECT_NAME=$1
if [ -z "$PROJECT_NAME" ]; then
    echo "No project name specified."
    exit 1
fi

# Collect informations
VERSION=$(mvn -B -pl ${PROJECT_NAME} help:evaluate -Dexpression=project.version -q -DforceStdout=true)
JAR_FILE=$(mvn -B -pl ${PROJECT_NAME} help:evaluate -Dexpression=jar_file -q -DforceStdout=true)
BASE_DIRECTORY=$(mvn -B -pl ${PROJECT_NAME} help:evaluate -Dexpression=project.basedir -q -DforceStdout=true)
TARGET_DIRECTORY=$(mvn -B -pl ${PROJECT_NAME} help:evaluate -Dexpression=project.build.directory -q -DforceStdout=true)

# Build the full classpath txt file
mvn  -B -pl ${PROJECT_NAME} dependency:build-classpath -Dmdep.outputFile=classpath.txt -Dmdep.regenerateFile=true -q
cat ${BASE_DIRECTORY}/classpath.txt | sed $'s/\:/\\\n/g' | sed 's/^/file:/' > ${BASE_DIRECTORY}/final-classpath.txt
echo -e "\nfile:${JAR_FILE}" >> ${PROJECT_NAME}/final-classpath.txt

# Upload the docker image and get the sha
DIGEST=$(mvn -B -pl ${PROJECT_NAME} clean package docker:build docker:push -Ddocker.username=${DOCKER_REGISTRY} -Ddocker.password=${DOCKER_PASSWORD} | grep "digest:" | sed 's/^.*digest: //' | sed 's/size:.*//')

# Generate the application CR
java -jar \
  /Users/andreatp/workspace/cloudflow/core/cloudflow-cr-generator/target/scala-2.12/cloudflow-cr-generator-assembly-2.0.25-NIGHTLY20210429-29-80666a4b-20210505-1430.jar \
  -n ${PROJECT_NAME} \
  -v ${VERSION} \
  -b ${BASE_DIRECTORY}/src/main/blueprint/blueprint.conf \
  -c ${BASE_DIRECTORY}/final-classpath.txt \
  -i ${PROJECT_NAME}=docker.io/${DOCKER_REGISTRY}/${PROJECT_NAME}@${DIGEST} > ${TARGET_DIRECTORY}/${PROJECT_NAME}.json

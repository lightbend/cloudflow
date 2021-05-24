mvn clean
mvn package cloudflow:extract-streamlets docker:build cloudflow:push-images -Ddocker.username=${DOCKER_USERNAME} -Ddocker.password=${DOCKER_PASSWORD}
mvn cloudflow:build-app

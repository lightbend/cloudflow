mvn clean
mvn package cloudflow:extract-streamlets cloudflow:push-images -Ddocker.username=${DOCKER_USERNAME} -Ddocker.password=${DOCKER_PASSWORD}
mvn cloudflow:build-app

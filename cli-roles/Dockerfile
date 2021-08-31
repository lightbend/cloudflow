FROM openjdk:11

RUN curl -sL http://cloudflow.io/docs/get.sh | bash -s -- -v 2.3.0-RC3 -V -u --yes && \
  apt-get update && \
  apt-get install -y apt-transport-https ca-certificates curl && \
  curl -fsSLo /usr/share/keyrings/kubernetes-archive-keyring.gpg https://packages.cloud.google.com/apt/doc/apt-key.gpg && \
  echo "deb [signed-by=/usr/share/keyrings/kubernetes-archive-keyring.gpg] https://apt.kubernetes.io/ kubernetes-xenial main" | tee /etc/apt/sources.list.d/kubernetes.list && \
  apt-get update && \
  apt-get install -y kubectl

COPY sensor-data-scala.json /sensor-data-scala.json

ENTRYPOINT ["sleep", "infinity"]

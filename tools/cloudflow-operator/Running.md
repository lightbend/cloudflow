Notes on running the operator locally:

 - spawn a cluster
 - prepare the cluster
 - helm uninstall cloudflow -n cloudflow
 - kubectl apply -f kafka-secret.yaml -n cloudflow
 - in a separate shell start `telepresence` (https://www.telepresence.io/)
 - sbt run :-)

Publishing a docker image:

// TODO switch to lightbend repo
```
sbt -Ddocker.username=andreatp cloudflow-operator/docker:publish
```

Install from helm chart using the docker image:

```
helm upgrade -i cloudflow cloudflow-helm-charts/cloudflow \
  --version "2.0.24" \
  --set cloudflow_operator.image.name=andreatp/cloudflow-operator \
  --set cloudflow_operator.image.tag=2.0.24-NIGHTLY20210222-44-42f78225-20210310-1054 \
  --set cloudflow_operator.jvm.opts="-XX:MaxRAMPercentage=90.0 -XX:+UseContainerSupport" \
  --set kafkaClusters.default.bootstrapServers=cloudflow-strimzi-kafka-bootstrap.cloudflow:9092 \
  --namespace cloudflow
```

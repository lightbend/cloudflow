Notes on running the operator locally:

 - spawn a cluster (with cloudflow-it commands)
 - prepare the cluster (with cloudflow-it commands)
 - helm uninstall cloudflow -n cloudflow
 - kubectl apply -f kafka-secret.yaml -n cloudflow
 - in a separate shell start `telepresence` (https://www.telepresence.io/)
 - sbt run

Publishing a docker image:

```
sbt -Ddocker.username=lightbend cloudflow-operator/docker:publish
```

Install from helm chart using the docker image such as:
```
helm upgrade -i cloudflow cloudflow-helm-charts/cloudflow \
  --version "2.0.24" \
  --set cloudflow_operator.image.name=lightbend/cloudflow-operator \
  --set cloudflow_operator.image.tag=2.0.25-SNAP2-19-ff827053 \
  --set cloudflow_operator.jvm.opts="-XX:MaxRAMPercentage=90.0 -XX:+UseContainerSupport" \
  --set kafkaClusters.default.bootstrapServers=cloudflow-strimzi-kafka-bootstrap.cloudflow:9092 \
  --namespace cloudflow
```

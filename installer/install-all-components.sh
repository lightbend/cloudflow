kubectl create ns cloudflow



helm upgrade nfs-server-provisioner stable/nfs-server-provisioner \
  --install \
  --set storageClass.provisionerName=cloudflow-nfs \
  --namespace cloudflow


helm install cloudflow cloudflow-helm-charts/cloudflow --namespace cloudflow \
  --set cloudflow_operator.persistentStorageClass=nfs \
  --set cloudflow_operator.kafkaBootstrapservers=cloudflow-strimzi-kafka-bootstrap.cloudflow:9092


helm install strimzi strimzi/strimzi-kafka-operator --namespace cloudflow



cat > kafka-cluster.yaml << EOF
apiVersion: kafka.strimzi.io/v1beta1
kind: Kafka
metadata:
  name: cloudflow-strimzi
  namespace: cloudflow
spec:
  kafka:
    config:
      auto.create.topics.enable: false
      log.message.format.version: "2.3"
      log.retention.bytes: 112197632
      log.retention.hours: -1
      offsets.topic.replication.factor: 3
      transaction.state.log.min.isr: 2
      transaction.state.log.replication.factor: 3
    listeners:
      plain: {}
      tls: {}
    livenessProbe:
      initialDelaySeconds: 15
      timeoutSeconds: 5
    readinessProbe:
      initialDelaySeconds: 15
      timeoutSeconds: 5
    replicas: 3
    resources: {}
    storage:
      deleteClaim: false
      size: 1Gi
      type: persistent-claim
    version: 2.4.0
  zookeeper:
    livenessProbe:
      initialDelaySeconds: 15
      timeoutSeconds: 5
    readinessProbe:
      initialDelaySeconds: 15
      timeoutSeconds: 5
    replicas: 3
    resources: {}
    storage:
      deleteClaim: false
      size: 1Gi
      type: persistent-claim
EOF



cat > spark-values.yaml << EOF
rbac:
  create: true
serviceAccounts:
  sparkoperator:
    create: true
    name: cloudflow-spark-operator
  spark:
    create: true
    name: cloudflow-spark

enableWebhook: true
enableMetrics: true

controllerThreads: 10
installCrds: true
metricsPort: 10254
metricsEndpoint: "/metrics"
metricsPrefix: ""
resyncInterval: 30
webhookPort: 8080
sparkJobNamespace: ""
operatorImageName: "lightbend/sparkoperator"
operatorVersion: "2.0.7-cloudflow-spark-2.4.5-1.1.2-scala-2.12"
---
kind: Service
apiVersion: v1
metadata:
  name: cloudflow-webhook
  labels:
    app.kubernetes.io/name: sparkoperator
spec:
  selector:
    app.kubernetes.io/name: sparkoperator
    app.kubernetes.io/version: 2.0.7-cloudflow-spark-2.4.5-1.1.2-scala-2.12
EOF


 helm install spark-operator incubator/sparkoperator \
    --namespace cloudflow \
    --values="spark-values.yaml" \
    --version "0.6.7"


 helm install flink-operator \
  https://github.com/lightbend/flink-operator/releases/download/v0.8.2/flink-operator-0.8.2.tgz \
  --namespace cloudflow \
  --set operatorImageName="lightbend/flinkk8soperator" \
  --set operatorVersion="v0.5.0"




kubectl apply -f kafka-cluster.yaml


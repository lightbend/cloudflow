Notes on running the operator locally:

 - spawn a cluster
 - prepare the cluster
 - helm uninstall cloudflow -n cloudflow
 - kubectl apply -f kafka-secret.yaml -n cloudflow
 - in a separate shell start `telepresence` (https://www.telepresence.io/)
 - sbt run :-)

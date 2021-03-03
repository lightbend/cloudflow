Notes on running the operator locally:

 - spawn a cluster
 - prepare the cluster
 - helm uninstall cloudflow -n cloudflow
 - kubectl apply -f kafka-secret.yaml -n cloudflow
 - sbt run :-)

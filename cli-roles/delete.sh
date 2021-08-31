
kubectl delete clusterrole cli-check-cr
kubectl delete role cli-check-r -n my-namespace
kubectl delete role cli-check-r-kafka -n cloudflow

kubectl delete clusterrolebinding cli-check-cr-bindings
kubectl delete rolebinding cli-check-r-bindings -n my-namespace
kubectl delete rolebinding cli-check-r-kafka-bindings -n cloudflow

kubectl delete serviceaccount cli-check -n my-namespace

kubectl delete deployment test-node -n my-namespace

export TILLER_NAMESPACE=default
export TILLER_SERVICE_ACCOUNT=tiller

kubectl create ns $TILLER_NAMESPACE
kubectl create serviceaccount --namespace $TILLER_NAMESPACE $TILLER_SERVICE_ACCOUNT
kubectl create clusterrolebinding $TILLER_NAMESPACE:tiller --clusterrole=cluster-admin --serviceaccount=$TILLER_NAMESPACE:$TILLER_SERVICE_ACCOUNT
helm init --wait --service-account $TILLER_SERVICE_ACCOUNT --upgrade --tiller-namespace=$TILLER_NAMESPACE

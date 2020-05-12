#!/bin/bash

kubectl delete pod -n lightbend --all
kubectl delete deployment -n lightbend --all
kubectl delete secret -n lightbend --all
kubectl delete pvc -n lightbend --all
kubectl delete configmaps -n lightbend --all
kubectl delete service -n lightbend --all
kubectl delete role -n lightbend --all
kubectl delete rolebinding -n lightbend --all
kubectl delete sa -n lightbend --all
kubectl delete cloudflows.cloudflow-installer.lightbend.com -n lightbend --all
kubectl delete kafka -n lightbend --all
kubectl delete serviceaccounts -n lightbend --all

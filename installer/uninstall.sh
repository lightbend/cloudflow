#!/usr/bin/env bash

kubectl -n cloudflow delete cloudflow default
kubectl delete ns cloudflow
kubectl delete ns cloudflow-installer

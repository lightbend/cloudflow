#!/usr/bin/env bash

kubectl -n cloudflow delete cloudflow default
kubectl -n delete ns cloudflow-installer

#!/bin/sh
go mod tidy
go test github.com/lightbend/cloudflow/kubectl-cloudflow/... -v -tags=integration

#!/bin/sh
go mod tidy
CGO_ENABLED=0 go test -ldflags "-extldflags "-static"" github.com/lightbend/cloudflow/kubectl-cloudflow/... -v -tags="integration exclude_graphdriver_devicemapper exclude_graphdriver_btrfs containers_image_openpgp"

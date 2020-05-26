<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->


- [kubectl-cloudflow](#kubectl-cloudflow)
  - [Requirements](#requirements)
  - [Development Environment Setup](#development-environment-setup)
    - [Install go](#install-go)
    - [Install Dep](#install-dep)
    - [Set up your workspace and `$GOPATH`](#set-up-your-workspace-and-gopath)
    - [Checkout the CLI the "Golang way"](#checkout-the-cli-the-golang-way)
  - [Build](#build)
  - [Generating documentation](#generating-documentation)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

# kubectl-cloudflow
Cloudflow plugin for kubectl

## Requirements

NOTE ! For this program to work as a kubectl plugin you need to have kubectl 1.13 installed

Kubectl can be found here:
https://kubernetes.io/docs/tasks/tools/install-kubectl/

## Development Environment Setup

### Install go

NOTE ! You will require at least go 1.10+.  Check your version with `go version`.

Fedora:

`sudo dnf install golang`

Ubuntu:

Install from the binary at: https://golang.org/dl/
and follow the install instructions: https://golang.org/doc/install

(Latest is v1.10.1 as of this writing)

OSX:

`brew install golang` (latest known/tested version: 1.14)

### Set up your workspace and `$GOPATH`

- Decide on where you want to locate your Golang "workspace", which will also be your `$GOPATH`
    + For the sake of this example, let's assume you choose `$HOME/Development/src/cloudflow/go`)
    + Create that directory
    + Now add the following two exports to your bash/zsh config
    + `export GOPATH=$HOME/Development/src/cloudflow/go`
    + `export PATH=$PATH:$(go env GOPATH)/bin`
    + Source that config file and verify that the output of `go env GOPATH` matches what you have just set.

### Checkout the CLI

The CLI must be cloned in any directory outside the `$GOPATH`, this change came about when we switched from `dep` to `go mod`.

## Build

Build the project

`go build`

Install the binary in your `$(GOPATH)/bin` directory

`go install`

Once the binary is in your `$(GOPATH)/bin` directory then you should be able to use it with `kubectl cloudflow` as long as the bin directory is on your `$PATH`.

## Generating documentation

```
kubectl cloudflow documentation generate -f md -p /user-guide/cli-reference
```

## Release to Bintray

These steps will create a new named release on bintray under `cloudflow-tools` with convention `RELEASE.BUILD-HASH` (i.e. `1.0.1.86-687f811`).

1. Setup your bintray credentials in `~/.lightbend/cloudflow`. Ex)
    
    ```
    realm = Bintray
    host = dl.bintray.com
    user = mybintrayusername
    password = mybintraytoken
    ```
2. Checkout `master` branch.
3. Call `make release RELEASE_VERSION`. Ex)
    
    ```
    $ make release 1.0.1
    ./build-and-release.sh 1.0.1
    Release tag specified: 1.0.1
    Branch not specified. Detected: master
    
    Building Pipectl from branch master...
    
    Updating dependencies...
    
    Building binaries for version 86-687f811...
    Building MacOS as binaries/amd64/darwin/kubectl-cloudflow...
    Building Linux as binaries/amd64/linux/kubectl-cloudflow...
    Building Windows as binaries/amd64/windows/kubectl-cloudflow.exe...
    
    On master branch. Releasing binaries to Bintray...
    Releasing MacOS archive...
    Releasing Linux archive...
    Releasing Windows archive...
    Release succeeded.
    
    Download links
    https://bintray.com/lightbend/cloudflow-tools/download_file?file_path=kubectl-cloudflow-1.0.1.86-687f811-darwin-amd64.tar.gz
    https://bintray.com/lightbend/cloudflow-tools/download_file?file_path=kubectl-cloudflow-1.0.1.86-687f811-linux-amd64.tar.gz
    https://bintray.com/lightbend/cloudflow-tools/download_file?file_path=kubectl-cloudflow-1.0.1.86-687f811-windows-amd64.tar.gz
    
    ...
    ```

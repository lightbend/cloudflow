# This is the makefile for  building, testing, and installing the kubectl plugin

SHELL := /bin/bash

# Get the second argument for upload
ifeq (release,$(firstword $(MAKECMDGOALS)))
  RELEASE_TAG := $(wordlist 2,$(words $(MAKECMDGOALS)),$(MAKECMDGOALS))
  ifeq ($(RELEASE_TAG),)
    $(error You must specify a release tag for the release)
  endif
  $(eval $(RELEASE_TAG):;@:) # Make sure arg is not treated as a rule
endif


all: help

test: ## Builds and test the plugin
	./build-and-test.sh

release: ## Builds and uploads and releases a version tagged with RELEASE_TAG
	- ./build-and-release.sh $(RELEASE_TAG)

install: ## Installs a local binary build
	- go install

help:  ## Print help for targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(lastword $(MAKEFILE_LIST)) | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-40s\033[0m %s\n", $$1, $$2}'

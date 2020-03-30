# Cloudflow Integration Test Suite

This folder hosts the integration tests for Cloudflow.


## Running the integration test suite
### Prerequisites
You must have a working installation of Go v1.14 or later.
You must have `kubectl` installed and configured to talk to the target cluster.
You must have an installed Cloudflow cluster available for the integration tests.

### Manual Execution
From the `itest` sub-directory, run `ginkgo -v` or `go test`

`Ginkgo` provides a nice textual overview of the executed tests.
The `-v` (verbose) is required to output a message for every executed test.

`go test` can be used as an alternative but it produces a slightly less attractive output.

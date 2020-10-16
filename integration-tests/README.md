# Cloudflow Integration Test Suite

This folder hosts the integration tests for Cloudflow.


## Running the integration test suite
### Prerequisites
You must have a working installation of Go v1.14 or later.
You must have `kubectl` installed and configured to talk to the target cluster.
You must have an installed Cloudflow cluster available for the integration tests.
You must build the swiss-knife app with `sbt buildApp` in the swiss-knife directory and copy the app CR file to the `itest/resources` directory if you want to modify the swiss-knife app that is used in the integration tests.
The images used in the swiss-knife app must be pushed to a public repository, since the integration tests do not provide username and password to `kubectl cloudflow deploy`. 
By default the swiss-knife app Docker images are published to `docker.io/lightbend`.
The target-env.sbt in the swiss-knife directory should contain the following:

    ThisBuild / cloudflowDockerRegistry := Some("docker.io")
    ThisBuild / cloudflowDockerRepository := Some("lightbend")

This will setup sbt to push the swiss-knife app images to the `docker.io/lightbend` Docker registry. From within the `swiss-knife` directory, build the swiss-knife app and copy the app CR file with:
    
    sbt buildApp
    cp target/swiss-knife.json ../itest/resources

### Manual Execution
From the `itest` sub-directory, run `PATH=$PWD:$PATH ginkgo -v`

`Ginkgo` provides a nice textual overview of the executed tests.
The `-v` (verbose) is required to output a message for every executed test.

`go test` can be used as an alternative but it produces a slightly less attractive output.

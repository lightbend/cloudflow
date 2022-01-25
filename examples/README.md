# Cloudflow examples 

This directory contains examples showcasing different Cloudflow features.

## Prerequisites

  All examples must be built and tested using JDK8.

  To build the examples using the latest Cloudflow release need to have the environment variable:
  ```
  CLOUDFLOW_VERSION
  ```
  pointing to the latest available release, otherwise re-build everything locally.

## Examples

- sensor-data-scala - A simple Akka based pipeline that processes events from a wind turbine farm. (Scala version)
- tensorflow-akka - A simple pipeline that scores the quality of wines using a TensorFlow model.
- connected-car-cluster-sharding - An example that uses cluster sharding.
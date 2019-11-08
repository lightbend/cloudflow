# Cloudflow Streamlets Library

## Publishing a development build

Run the `internalRelease` sbt command to build and publish an development build.
The command is using the `sbt-release` process, with a modified build version.

The build version looks like `1.0.1-940-b35c9a59`, with the following format: `<version>-<commit-count>-<commit-hash>`.

## Publishing a release

Run the `release` sbt command from `sbt-release` to build and publish a release.

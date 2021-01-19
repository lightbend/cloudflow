# Tooling

This sub-project contains various utilities used in internal devlopment.

### Cli

 - `sbt regenerateGraalVMConfig` regenerate the GraalVM configuration in `cloudflow-cli/src/main/resources/META-INF/native-image`, remember to run it after you change code in the Cli!
 - `sbt tooling/runMain scopt.DocGenMain` generate a Cli documentation file in `cloudflow.adoc`
 - `sbt tooling/runMain cli.SamplesGenerator` generate a full configuration file in `cloudflow-cli/samples/full-config.conf`

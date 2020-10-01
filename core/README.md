# Cloudflow Streamlets Library

## Prerequisites

  The Cloudflow streamlet library must be built and tested using JDK8.

## Building and testing the Cloudflow Operator image

In order to test changes made to the `cloudflow-operator` module on GKE there are several steps involved:

1. [Set up](https://cloud.google.com/container-registry/docs/pushing-and-pulling) your local environment to work with the GCP container registry 
2. Update the docker image settings for the `operator` module in [build.sbt](build.sbt):

```
ImageName(
  registry =Some("<gcp_hostname>"),
  namespace = Some("<project_id>"),
  repository = "cloudflow-operator",
  tag = Some(cloudflowBuildNumber.value.asVersion)
)
```

3. Open the sbt console by typing `sbt`
4. Switch to the `cloudflow-operator` project: `project cloudflow-operator`
5. Build and push the docker image: `dockerBuildAndPush`
6. Deploy the operator

Set the `cloudflow_operator.image.name` value when installing the [helm chart](https://github.com/lightbend/cloudflow-helm-charts), i.e) 

```
helm install cloudflow ./cloudflow --namespace cloudflow \
--set cloudflow_operator.image.name=<gcp_hostname>/<project_id>/cloudflow-operator \
--set cloudflow_operator.image.tag=<docker-image-tag> \
--set cloudflow_operator.image.pullPolicy=Always \
--set cloudflow_operator.logLevelRoot=DEBUG
```

Update the operator image settings in [shared.sh](../installer/deprecated-installer/common/shared.sh) if using the deprecated installer (Cloudflow 1.3.3 and earlier):

```
export operatorImageName="<gcp_hostname>/<project_id>/cloudflow-operator"
export operatorImageTag="<docker-image-tag>"
```

**note**: don't forget to replace `<gcp_hostname>`, `<project_id>` and `<docker-image-tag>` by your specific values. 
7. Follow the GKE installer [instructions](../installer/README.md) to setup Cloudflow on GKE with the modified `cloudflow-operator` image.

## Publishing a development build

Run the `internalRelease` sbt command to build and publish an development build.
The command is using the `sbt-release` process, with a modified build version.

The build version looks like `1.0.1-940-b35c9a59`, with the following format: `<version>-<commit-count>-<commit-hash>`.

## Publishing a release

Run the `release` sbt command from `sbt-release` to build and publish a release.

# Cloudflow Installer
This project is the CloudFlow Installer, implemented as a Kubernetes operator.

## Development

Required tools:

* `sbt`
* `make`
* `helm`
* `kubectl`
* `gcloud` - with docker [configured to access GCR](https://cloud.google.com/container-registry/docs/advanced-authentication).

## Installing

Currently the CloudFlow Installer operator is installed using `kubectl` and a yaml file located in the `/test` directory. This has been tested to work on the following Kubernetes distributions:

- Openshift 3.11
- GKE (Various versions)

To install the CloudFlow Installer do the following:

1) Clone this repo
2) During development, the latest version of the docker image may not be pushed, so build the latest version of the container using `sbt dockerBuildAndPush`
3) Update the `image:` label in the `test/installer-deployment.yaml` file with the new docker container path
4) `kubectl apply -f test/installer-deployment.yaml` 
5) Check the status of the deployments in the `cloudflow-installer` namespace

Install Cloudflow (GKE)

1) `kubectl apply -f test/cloudflowinstance_gke.yaml`
2) Check the status of the deployments in the `cloudflow` namespace

>Note that if you are installing on `openshift` use the `cloudflowinstance_openshift.yaml` file instead.

>Note If you have issues login to the GCR container repo on Mac, please see this post on SO: https://stackoverflow.com/questions/49780218/docker-credential-gcloud-not-in-system-path

## Relation with other projects

The Cloudflow Installer uses the content of a number of Helm charts.

Here the detail:

[![relationship with other projects](doc-images/cloudflow-installer-relationship-with-other-projects.png)](https://www.lucidchart.com/invitations/accept/0a9e1636-03d9-4b66-bb5e-3fe9a281f1e1)

### Code formatting

The project uses `scalafmt` for formatting the source code, if you have VS Code + Metals you do not have to do anything.

If you are using any other editor combination, the Scalafmt plugin has also been included, use any of the following commands to format the code:

    myproject/scalafmt          Format main sources of myproject project
    myproject/test:scalafmt     Format test sources of myproject project
    scalafmtCheck               Check if the scala sources under the project has been formatted.
    scalafmtSbt                 Format *.sbt and project/*.scala files.
    scalafmtSbtCheck            Check if the files has been formatted by scalafmtSbt.
    scalafmtOnly                Format a single given file.
    scalafmtAll                 Execute the scalafmt task for all configurations in which it is enabled.
    scalafmtCheckAll            Execute the scalafmtCheck task for all configurations in which it is enabled.

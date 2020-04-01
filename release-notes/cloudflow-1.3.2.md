# Cloudflow 1.3.2 Release Notes

Cloudflow 1.3.2 contains the following changes:

- Fixed a bug that was causing semi-random deletions of application resources after multiple subsequent deploys. This was caused by invalid instructions (so-called "owner references") for the Kubernetes garbage collector that were added to support clean undeploy/uninstall.
- Fixed another side-effect of the above root cause, which could lead to broken Cloudflow installs on some platforms.
- Fixed an issue with generated Kafka consumer IDs that could cause incorrect consumption in case a streamlet class was added to a blueprint multiple times. Kafka consumer IDs are once again globally unique.
- Fixed an issue with the `kubectl cloudflow status` command not reporting the correct rolled up application status for applications that include Flink-based streamlets.
- Fixed an issue with the configuration of streamlet parameters during local running. Internal variable references inside the `local.conf` file were not resolved correctly.
- Fixed an incompatibility in the Enterprise installer that would fail to install the Cloudflow console on Kubernetes version 1.16 and higher. Cloudflow should once again be compatible with all versions of Kubernetes 1.11 and higher (verified up to 1.16 as of this release).
- Fixed an issue with the Enterprise installer that could fail the install if Cloudflow was not installed in the `cloudflow` namespace.
- Added testkit support for explicitly setting volume mount paths to enable testing of streamlets that include volume mounts. Contributed by @claudio-scandura

**Known issues with this release:**
No known issues.


**Cloudflow 1.3.2 was tested on the following Kubernetes distributions/versions:**

- Google Kubernetes Engine (GKE) using Kubernetes 1.13, 1.15, and 1.16
- Amazon Elastic Kubernetes Service (EKS) using Kubernetes 1.14
- Azure Kubernetes Service (AKS) using Kubernetes 1.13 (Enterprise edition only)
- Openshift 3.11 using Kubernetes 1.11 (Enterprise edition only)
- Openshift 4.3 using Kubernetes 1.16 (Enterprise edition only)

**NOTE**: We are planning on removing the dual-installer architecture in Cloudflow 1.4/1.5, which will enable OSS installation and upgrade support for all platforms currently supported only by the Enterprise installer.

Cloudflow 1.3.2 can be installed on a Kubernetes cluster in the usual way using either the OSS or Enterprise installers. For this release we recommend a clean install instead of an in-place upgrade to make sure everything gets upgraded without issues.

**The Cloudflow 1.3.2 `kubectl` plugin can be downloaded using one of the following links:**

- Linux: https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-1.3.2.200-0d0f745-linux-amd64.tar.gz
- MacOS: https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-1.3.2.200-0d0f745-darwin-amd64.tar.gz
- Windows: https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-1.3.2.200-0d0f745-windows-amd64.tar.gz

**The roadmap for the Cloudflow 1.4 release, scheduled for April/May 2020, currently looks like this:**

- Add support for applications that span multiple docker images instead of a single shared one
- Add a new implementation of the "local running" (aka "sandbox") feature to support multiple-image applications
- Add an improved configuration system allowing users to override various levels of configuration at deployment time, including Kubernetes (environment variables, resource requests/limits, etc.) and runtime-specific values such as Akka/Spark/Flink properties.

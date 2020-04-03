# Cloudflow 1.3.3 Release Notes (April 3rd 2020)

Cloudflow 1.3.3 contains the following changes:

- During the 1.3.2 release process a PR was accidentily merged that limited the Enterprise installer to the use of `kubectl` version 1.18 or higher.
- The Enterprise installer bootstrap script contained references to Kubernetes API versions that were not supported below Kubernetes 1.14 on some distributions. The Enterprise installer is now once again compatible with Kubernetes 1.11 and higher.
- The OSS installer was dependent on a specific Kubernetes cluster setup with two node pools, e.g. the cluster setup provided by the utility cluster creation script. We have removed this limitation so other, more standard clusters can also be used for Cloudflow installations.
- The OSS uninstall script left a role binding behind which produced a warning on reinstall.

**Known issues with this release:**
- Due to the changes in the OSS installer and the cluster creation utility scripts, users installing Cloudflow 1.3.3 on clusters created with the previously released cluster creation scripts might end up with an unused node pool. The "StrimziKafka" node pool can be safely deleted.
- The Enterprise uninstaller might not leave a completely clean system behind, e.g. there might be a few resources that will not be removed after removing the main Cloudflow installation. This will be fixed ASAP.

**Cloudflow 1.3.3 was tested on the following Kubernetes distributions/versions:**

- Google Kubernetes Engine (GKE) using Kubernetes 1.14, 1.15, and 1.16
- Amazon Elastic Kubernetes Service (EKS) using Kubernetes 1.14
- Azure Kubernetes Service (AKS) using Kubernetes 1.15 (Enterprise edition only)
- Openshift 3.11 using Kubernetes 1.11 (Enterprise edition only)
- Openshift 4.3 using Kubernetes 1.16 (Enterprise edition only)

**NOTE**: We are planning on removing the dual-installer architecture in Cloudflow 1.4/1.5, which will enable OSS installation and upgrade support for all platforms currently supported only by the Enterprise installer.

Cloudflow 1.3.3 can be installed on a Kubernetes cluster in the usual way using either the OSS or Enterprise installers. For this release we recommend a clean install instead of an in-place upgrade to make sure everything gets installed without issues.

**The Cloudflow 1.3.3 `kubectl` plugin can be downloaded using one of the following links:**

- Linux: https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-1.3.3.211-87a608c-linux-amd64.tar.gz
- MacOS: https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-1.3.3.211-87a608c-darwin-amd64.tar.gz
- Windows: https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-1.3.3.211-87a608c-windows-amd64.tar.gz

**The roadmap for the Cloudflow 1.4 release, scheduled for April/May 2020, currently looks like this:**

- Add support for applications that span multiple docker images instead of a single shared one
- Add a new implementation of the "local running" (aka "sandbox") feature to support multiple-image applications
- Add an improved configuration system allowing users to override various levels of configuration at deployment time, including Kubernetes (environment variables, resource requests/limits, etc.) and runtime-specific values such as Akka/Spark/Flink properties.

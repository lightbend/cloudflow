# Cloudflow 2.0.12 Release Notes (October 29th, 2020)

Today we are happy to announce the availability of Cloudflow 2.0.12. 

This patch release fixes a number of issues that have been reported since 2.0.11.
For existing users of Cloudflow 2.0.10 and 2.0.11 it is important to note that Cloudflow must be upgraded with the helm charts, as described in the documentation. The Cloudflow kubectl plugin must also be upgraded to 2.0.12 (links below) and existing applications must be rebuilt with the 2.0.12 sbt-cloudflow plugin. Stricter verification has been put in place that all of these have been upgraded to 2.0.12 to prevent incompatibility issues.

We have moved from Gitter to [Zulip](https://cloudflow.zulipchat.com/), please sign up and continue to chat with us there. 

Details of the fixes in this release can be found [here](https://github.com/lightbend/cloudflow/releases/tag/v2.0.12). 

**The Cloudflow 2.0.12 `kubectl` plugin can be downloaded using one of the following links:**

* [Linux](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.12.824-4c1941a-linux-amd64.tar.gz)
* [MacOS](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.12.824-4c1941a-darwin-amd64.tar.gz)
* [Windows](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.12.824-4c1941a-windows-amd64.tar.gz)

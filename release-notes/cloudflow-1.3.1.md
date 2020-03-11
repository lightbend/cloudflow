# Cloudflow 1.3.1 Release Notes

Cloudflow 1.3.1 is a patch release and contains the following changes:

- Upgraded to Spark 2.4.5 and Spark Operator 0.6.7
- Upgraded to Flink 1.10 and Flink Operator 0.8.2
- Upgraded to Kafka 2.4.0 and Strimzi operator 0.16.2
- Upgraded to Lightbend Console 1.2.9 (Enterprise edition only)
- Upgraded to include Alpakka Kafka 2.0.2, which includes significant speed ups. Also updated the Akka streams Streamlet APIs to match.
- Streamlet HTTP endpoints now get predictable port numbers instead of randomized ones
- Support for fully uninstalling Cloudflow including all associated resources, dependencies and deployed applications
- The CLI now supports Cloudflow installations in non-default namespaces by auto-detecting the installation namespace
- The OSS installer now supports Amazon Elastic Kubernetes Service (EKS)

**Known issues with this release:**

- The Enterprise edition cannot be installed on K8s version 1.16 or higher due to a compatibility issue with the Lightbend Console.
- The `kubectl cloudflow status` command does not report a correct rollup status for the entire application when it includes Flink streamlets; e.g. it reports `Pending` even though all individual streamlets are `Running`.

**Cloudflow 1.3.1 was tested on the following Kubernetes distributions/versions:**

- Google Kubernetes Engine (GKE) using Kubernetes 1.13, 1.15, and 1.16
- Amazon Elastic Kubernetes Service (EKS) using Kubernetes 1.14
- Azure Kubernetes Service (AKS) using Kubernetes 1.13 (Enterprise edition only)
- Openshift 3.11 using Kubernetes 1.11 (Enterprise edition only)
- Openshift 4.3 using Kubernetes 1.16 (Enterprise edition only)

**NOTE**: We are planning on removing the dual-installer architecture in Cloudflow 1.4/1.5, which will enable OSS installation and upgrade support for all platforms currently supported only by the Enterprise installer.

Cloudflow 1.3.1 can be installed on a Kubernetes cluster in the usual way using either the OSS or Enterprise installers. Enterprise installations can be upgraded in-place by running the 1.3.1 installation bootstrap script (and choosing the same storage classes).

**The Cloudflow 1.3.1 CLI can be downloaded using one of the following links:**

- Linux:
- MacOS:
- Windows:

**The roadmap for the Cloudflow 1.4 release, scheduled for April/May 2020, currently looks like this:**

- Add support for applications that span multiple docker images instead of a single shared one
- Add a new implementation of the "local running" (aka "sandbox") feature to support multiple-image applications
- Add an improved configuration system allowing users to override various levels of configuration at deployment time, including Kubernetes (environment variables, resource requests/limits, etc.) and runtime-specific values such as Akka/Spark/Flink properties.

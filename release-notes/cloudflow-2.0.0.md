# Cloudflow 2.0.0 Release Notes (June 10th 2020)

Today we are proud to announce the availability of Cloudflow 2.0.0. 

Highlights of Cloudflow 2.0.0 are a new installer, a new configuration system, multi-image support, multi-image local runner, connecting streamlets to topics on any Kafka broker(s) and a new blueprint format to connect streamlets to topics.

# Highlights of Cloudflow 2.0.0
The key features of the 2.0.0 release are:
- Configuration file support (#410) in `kubectl cloudflow deploy` and `kubectl cloudflow configure`
- Akka configuration
- Kubernetes pods and containers configuration, including environment variables and java options (#431)
- Configuring Flink resource limits, requests, env vars and runtime config. (#453)
- Configuring env, java options and resource requirements for Spark (#450)
- Topics configuration (#427)
- Changed blueprint, streamlets connect to topics (#251)
- Multi runLocal (#420)
- Multi-image support (#362)
- Open sourced the new installer (#409)

Other notable changes relative to 1.3.3 include:
- Updated to Akka 2.6.5 (#398)
- Updated to Alpakka Kafka 2.0.3. (#413)
- Akka Cluster support (#234)
- Protobuf support, Scala API (#405)
- Removed old plugins (#429)
- Default to less resources when using Spark (#483)
- Integration tests for configuration (#475, #478)
- Sort status report by streamlet name, desc (#466)
- Fix for HttpServerLogic (#243)
- Fix for Flink, auto.offset.reset to earliest (#229)
- Fix for printing missing pods in kubectl cloudflow status.
- Fix for kubectl cloudflow status (Missing and CrashloopBackoff) (#380)
- Fix for default empty string config parameter. (#452, #467, #474)
- Fix for Kubernetes API versions (#438)
- Fix for Akka cluster config (#357)
- Fix for Call Record Aggregator test (#249)
- Fix for truncating label of kafka topic (#241)
- More stable Cloudflow operator, recovers from conflicts, retries up to a minute to get the resource. (#248, #454)
- Deprecate ..WithOffsetContext (#252)
- Integrated docs in the cloudflow repo (#381)
- Documentation versions support (#384)
- Deprecated bash / helm based installer script (#470)
- Moved NFS out of bootstrap script (#440)
- Added create cluster script for AKS (#412)
- Updated Integration Tests (#446)
- Getting started templates (#244)
- CI improvement, added integration tests project
- Remove kafka nodepool in create cluster for EKS (#226)

# Compatibility
Cloudflow 2.0.0 is a breaking change compared to 1.3.3. 
You will have to undeploy your existing applications and uninstall Cloudflow 1.3.3, install Cloudflow 2.0.0 and `kubectl cloudflow`, and update your projects to use sbt-cloudflow 2.0.0.

**Cloudflow 2.0.0 was tested on the following Kubernetes distributions/versions:**

- Google Kubernetes Engine (GKE) using Kubernetes 1.14 and 1.16

**The Cloudflow 2.0.0 `kubectl` plugin can be downloaded using one of the following links:**

- [Linux](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.0.456-352f6cc-linux-amd64.tar.gz)
- [MacOS](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.0.456-352f6cc-darwin-amd64.tar.gz)
- [Windows](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.0.456-352f6cc-windows-amd64.tar.gz)

# Credits
For this release we had the help of 12 committers – thank you all very much!

| Author | Commits | Lines added | Lines removed |
| ------ | ------- | ----------- | ------------- |
| [<img width="20" alt="RayRoestenburg" src="https://avatars1.githubusercontent.com/u/156425?v=4&amp;s=40"/> **RayRoestenburg**](https://github.com/RayRoestenburg) | 106 | 14834 | 8997 |
| [<img width="20" alt="maasg" src="https://avatars3.githubusercontent.com/u/874997?v=4&amp;s=40"/> **maasg**](https://github.com/maasg) | 73 | 14956 | 7418 |
| [<img width="20" alt="yuchaoran2011" src="https://avatars0.githubusercontent.com/u/1168769?v=4&amp;s=40"/> **yuchaoran2011**](https://github.com/yuchaoran2011) | 24 | 5642 | 2002 |
| [<img width="20" alt="debasishg" src="https://avatars3.githubusercontent.com/u/107231?v=4&amp;s=40"/> **debasishg**](https://github.com/debasishg) | 13 | 639 | 555 |
| [<img width="20" alt="agemooij" src="https://avatars2.githubusercontent.com/u/46568?v=4&amp;s=40"/> **agemooij**](https://github.com/agemooij) | 6 | 127 | 9 |
| [<img width="20" alt="olofwalker" src="https://avatars3.githubusercontent.com/u/23613882?v=4&amp;s=40"/> **olofwalker**](https://github.com/olofwalker) | 4 | 38 | 60 |
| [<img width="20" alt="skyluc" src="https://avatars0.githubusercontent.com/u/1098830?v=4&amp;s=40"/> **skyluc**](https://github.com/skyluc) | 3 | 445517 | 883348 |
| [<img width="20" alt="rstento" src="https://avatars3.githubusercontent.com/u/22889339?v=4&amp;s=40"/> **rstento**](https://github.com/rstento) | 3 | 118 | 112 |
| [<img width="20" alt="skonto" src="https://avatars1.githubusercontent.com/u/7945591?v=4&amp;s=40"/> **skonto**](https://github.com/skonto) | 3 | 16 | 37 |
| [<img width="20" alt="nolangrace" src="https://avatars2.githubusercontent.com/u/1775305?v=4&amp;s=40"/> **nolangrace**](https://github.com/nolangrace) | 2 | 177 | 8 |
| [<img width="20" alt="claudio-scandura" src="https://avatars0.githubusercontent.com/u/1486771?v=4&amp;s=40"/> **claudio-scandura**](https://github.com/claudio-scandura) | 1 | 158 | 19 |
| [<img width="20" alt="mrooding" src="https://avatars2.githubusercontent.com/u/5998869?v=4&amp;s=40"/> **mrooding**](https://github.com/mrooding) | 1 | 1 | 1 |
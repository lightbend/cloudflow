# Cloudflow 2.0.5 Release Notes (July 6th, 2020)

Today we are happy to announce the availability of Cloudflow 2.0.5. 

Highlights of Cloudflow 2.0.5 are improved documentation, installer improvements and support for Akka 2.6.6.

# Highlights of Cloudflow 2.0.5
- [Documentation](https://cloudflow.io/docs/current/index.html) for 2.0 features including a [migration guide from 1.3.x to 2.0.x](https://cloudflow.io/docs/current/project-info/migration-1_3-2_0.html). 
- More configurable installer, making it possible to pull docker images from user-specified docker registries.
- Update to Akka 2.6.6 [#516](https://github.com/lightbend/cloudflow/issues/516) [#530](https://github.com/lightbend/cloudflow/pull/530)
- Config for local runner now follows the new format introduced in 2.0.0 [#541](https://github.com/lightbend/cloudflow/issues/541) [#552](https://github.com/lightbend/cloudflow/pull/552)
- Added missing check for port bound to many topics [#549](https://github.com/lightbend/cloudflow/issues/549) [#547](https://github.com/lightbend/cloudflow/pull/547)

**The Cloudflow 2.0.5 `kubectl` plugin can be downloaded using one of the following links:**

* [Linux](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.5.510-18276ac-linux-amd64.tar.gz)
* [MacOS](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.5.510-18276ac-darwin-amd64.tar.gz)
* [Windows](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.5.510-18276ac-windows-amd64.tar.gz)

# Credits
For this release we had the help of 4 committers – thank you all very much!

| Author | Commits | Lines added | Lines removed |
| ------ | ------- | ----------- | ------------- |
| [<img width="20" alt="RayRoestenburg" src="https://avatars1.githubusercontent.com/u/156425?v=4&amp;s=40"/> **RayRoestenburg**](https://github.com/RayRoestenburg) | 26 | 256 | 612 |
| [<img width="20" alt="maasg" src="https://avatars3.githubusercontent.com/u/874997?v=4&amp;s=40"/> **maasg**](https://github.com/maasg) | 14 | 7386 | 1094 |
| [<img width="20" alt="yuchaoran2011" src="https://avatars0.githubusercontent.com/u/1168769?v=4&amp;s=40"/> **yuchaoran2011**](https://github.com/yuchaoran2011) | 11 | 766 | 240 |
| [<img width="20" alt="patriknw" src="https://avatars3.githubusercontent.com/u/336161?v=4&amp;s=40"/> **patriknw**](https://github.com/patriknw) | 1 | 46 | 40 |

# Cloudflow 2.0.7 Release Notes (July 23rd, 2020)

Today we are happy to announce the availability of Cloudflow 2.0.7. 

This patch release significantly reduces known security vulnerabilities in Docker images and JAR dependencies used in Cloudflow.

It also fixes some issues:
- Updated sbt-docer plugin to v1.6.0 [#563](https://github.com/lightbend/cloudflow/issues/563) [#583](https://github.com/lightbend/cloudflow/pull/583)
- Made it possible to modify Flink StreamExecutionEnvironment in FlinkStreamlet [#573](https://github.com/lightbend/cloudflow/pull/573)
- Allow Streamlet with empty StreamletShape [#572](https://github.com/lightbend/cloudflow/pull/572)
- Made base docker images for Cloudflow applications configurable [#509](https://github.com/lightbend/cloudflow/issues/509) [#566](https://github.com/lightbend/cloudflow/pull/566)
- Use streamlet name as ActorSystem name in AkkaStreamlet [#529](https://github.com/lightbend/cloudflow/issues/529) [#569](https://github.com/lightbend/cloudflow/pull/569)
- Changed akka-cluster-local.conf to randomize port and join cluster without seednodes [#568](https://github.com/lightbend/cloudflow/pull/568)
- Deprecated buildAndPublish [#562](https://github.com/lightbend/cloudflow/pull/562)

**The Cloudflow 2.0.7 `kubectl` plugin can be downloaded using one of the following links:**

* [Linux](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.7.604-c4feda6-linux-amd64.tar.gz)
* [MacOS](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.7.604-c4feda6-darwin-amd64.tar.gz)
* [Windows](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.7.604-c4feda6-windows-amd64.tar.gz)

# Credits
For this release we had the help of 5 committers – thank you all very much!

| Author | Commits | Lines added | Lines removed |
| ------ | ------- | ----------- | ------------- |
| [<img width="20" alt="RayRoestenburg" src="https://avatars1.githubusercontent.com/u/156425?v=4&amp;s=40"/> **RayRoestenburg**](https://github.com/RayRoestenburg) | 74 | 633 | 3314 |
| [<img width="20" alt="yuchaoran2011" src="https://avatars0.githubusercontent.com/u/1168769?v=4&amp;s=40"/> **yuchaoran2011**](https://github.com/yuchaoran2011) | 14 | 186 | 572 |
| [<img width="20" alt="franciscolopezsancho" src="https://avatars3.githubusercontent.com/u/1381621?v=4&amp;s=40"/> **franciscolopezsancho**](https://github.com/franciscolopezsancho) | 3 | 84 | 37 |
| [<img width="20" alt="maasg" src="https://avatars3.githubusercontent.com/u/874997?v=4&amp;s=40"/> **maasg**](https://github.com/maasg) | 2 | 96 | 48 |
| [<img width="20" alt="nolangrace" src="https://avatars2.githubusercontent.com/u/1775305?v=4&amp;s=40"/> **nolangrace**](https://github.com/nolangrace) | 2 | 12 | 11 |

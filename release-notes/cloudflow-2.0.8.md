# Cloudflow 2.0.8 Release Notes (August 19th, 2020)

Today we are happy to announce the availability of Cloudflow 2.0.8. 

This patch release significantly reduces known security vulnerabilities in Docker images and JAR dependencies used in Cloudflow.

Highlights of other improvements made in this release:
- [#630](https://github.com/lightbend/cloudflow/issues/630) Faster deployment. In some recent tests, deployment was as much as 40% faster. The sbt-cloudflow plugin puts SHA256 image digests of the pushed images directly into the Application CR json file, using [sbt-docker 1.8.0](https://github.com/marcuslonnberg/sbt-docker/releases/tag/v1.8.0). Because of this, `kubectl cloudflow deploy` does not have to pull the images to replace the digests in the Application CR, which increases the speed of the `kubectl cloudflow deploy`. With `--no-registry-credentials` flag you can now indicate that kubectl cloudflow does not need to create an image pull secret for the image. If you leave this flag out you have to specify credentials that can be used to pull the image on your cluster, preferrably using `--password-stdin` and `--username`. 
- [#564](https://github.com/lightbend/cloudflow/issues/564) Kafka external sharding strategy experimental feature for clustered Akka streamlets.
- [#624](https://github.com/lightbend/cloudflow/issues/624) JAVA_OPTS in kubernetes pod env var was not picked up for Flink streamlets. This is now fixed.
- [#633](https://github.com/lightbend/cloudflow/issues/633) Updated Cloudflow to Akka HTTP 10.2.0
- [#620](https://github.com/lightbend/cloudflow/issues/620) Resolved conflicting log implementations in Cloudflow
- [#597](https://github.com/lightbend/cloudflow/issues/597) Support many Application projects in one sbt project

**The Cloudflow 2.0.8 `kubectl` plugin can be downloaded using one of the following links:**

* [Linux](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.8.645-df682e0-linux-amd64.tar.gz)
* [MacOS](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.8.645-df682e0-darwin-amd64.tar.gz)
* [Windows](https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-2.0.8.645-df682e0-windows-amd64.tar.gz)

# Credits
For this release we had the help of 7 committers – thank you all very much!

| Author | Commits | Lines added | Lines removed |
| ------ | ------- | ----------- | ------------- |
| [<img width="20" alt="RayRoestenburg" src="https://avatars1.githubusercontent.com/u/156425?v=4&amp;s=40"/> **RayRoestenburg**](https://github.com/RayRoestenburg) | 31 | 1086 | 977 |
| [<img width="20" alt="franciscolopezsancho" src="https://avatars3.githubusercontent.com/u/1381621?v=4&amp;s=40"/> **franciscolopezsancho**](https://github.com/franciscolopezsancho) | 4 | 158 | 9 |
| [<img width="20" alt="olofwalker" src="https://avatars3.githubusercontent.com/u/23613882?v=4&amp;s=40"/> **olofwalker**](https://github.com/olofwalker) | 2 | 143 | 55 |
| [<img width="20" alt="nolangrace" src="https://avatars2.githubusercontent.com/u/1775305?v=4&amp;s=40"/> **nolangrace**](https://github.com/nolangrace) | 1 | 458 | 71 |
| [<img width="20" alt="raboof" src="https://avatars2.githubusercontent.com/u/131856?v=4&amp;s=40"/> **raboof**](https://github.com/raboof) | 1 | 11 | 9 |
| [<img width="20" alt="justinhj" src="https://avatars0.githubusercontent.com/u/753059?v=4&amp;s=40"/> **justinhj**](https://github.com/justinhj) | 1 | 3 | 3 |
| [<img width="20" alt="thomasschoeftner" src="https://avatars2.githubusercontent.com/u/5425970?v=4&amp;s=40"/> **thomasschoeftner**](https://github.com/thomasschoeftner) | 1 | 1 | 24 |
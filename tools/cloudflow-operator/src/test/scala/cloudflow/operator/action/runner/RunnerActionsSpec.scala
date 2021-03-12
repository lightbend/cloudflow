/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.operator.action.runner

import akka.datap.crd.App
import akka.kube.actions.{ CreateOrReplaceAction, DeleteAction, GetAction, OperatorAction }
import org.scalatest.{ ConfigMap => _, _ }
import com.typesafe.config._
import cloudflow.blueprint.{ Topic => BTopic, _ }
import cloudflow.blueprint.deployment._
import cloudflow.blueprint.BlueprintBuilder._
import cloudflow.operator.action._
import cloudflow.operator.action.runner.AkkaRunner.{ PrometheusExporterPortEnvVar, PrometheusExporterRulesPathEnvVar }
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.{
  ConfigMap,
  ConfigMapVolumeSource,
  EnvVarBuilder,
  ExecAction,
  Quantity,
  Secret,
  SecretBuilder
}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.jdk.CollectionConverters._
import scala.util.{ Success, Try }

class RunnerActionsSpec
    extends AnyWordSpec
    with Matchers
    with GivenWhenThen
    with EitherValues
    with Inspectors
    with TestDeploymentContext {

  case class Foo(name: String)
  case class Bar(name: String)
  val agentPaths = Map("prometheus" -> "/app/prometheus/prometheus.jar")
  val secret = new SecretBuilder().build()
  val akkaRunner = new AkkaRunner(ctx.akkaRunnerDefaults)

  "RunnerActions" should {
    "create resources for runners when there is no previous application deployment" in {

      Given("no current app and a new app")
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
      val egress = randomStreamlet().asEgress[Foo].withServerAttribute

      val ingressRef = ingress.ref("ingress")
      val egressRef = egress.ref("egress")

      val verifiedBlueprint = Blueprint()
        .define(Vector(ingress, egress))
        .use(ingressRef)
        .use(egressRef)
        .connect(BTopic("foos"), ingressRef.out, egressRef.in)
        .verified
        .right
        .value

      val appId = "def-jux-12345"
      val appVersion = "42-abcdef0"
      val image = "image-1"

      val currentApp = None
      val newApp = App.Cr(
        spec = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths),
        metadata = CloudflowApplicationSpecBuilder.demoMetadata)

      When("runner actions are created from a new app")
      val actions = akkaRunner.actions(newApp, currentApp, runners)

      Then("only 'create actions' must be created for every runner")
      val createActions = actions.collect {
        case c: CreateOrReplaceAction[_] => c
      }

      val createDeploymentActions = actions.collect {
        case p: OperatorAction[_, _, _, Try[Secret]] =>
          p.createAction(Success(secret)).asInstanceOf[CreateOrReplaceAction[Deployment]]
      }

      val configMaps = createActions.map(_.resource).collect {
        case configMap: ConfigMap => configMap
      }
      val akkaDeployments = createDeploymentActions.map(_.resource)

      val streamletDeployments = newApp.spec.deployments

      createActions.size + createDeploymentActions.size mustBe actions.size
      configMaps.size mustBe streamletDeployments.size
      akkaDeployments.size mustBe streamletDeployments.size
      configMaps.foreach { configMap =>
        assertConfigMap(configMap, newApp.spec, appId, appVersion, ctx)
      }
      akkaDeployments.foreach { deployment =>
        assertAkkaDeployment(deployment, configMaps, newApp.spec, appId, ctx)
      }
    }

    "update when the new applications requires the same runners as the current one" in {
      Given("a current app")
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
      val egress = randomStreamlet().asEgress[Foo].withServerAttribute

      val ingressRef = ingress.ref("ingress")
      val egressRef = egress.ref("egress")

      val verifiedBlueprint = Blueprint()
        .define(Vector(ingress, egress))
        .use(ingressRef)
        .use(egressRef)
        .connect(BTopic("foos"), ingressRef.out, egressRef.in)
        .verified
        .right
        .value

      val appId = "def-jux-12345"
      val appVersion = "42-abcdef0"
      val image = "image-1"

      val newApp = App.Cr(
        spec = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths),
        metadata = CloudflowApplicationSpecBuilder.demoMetadata)
      val currentApp = Some(newApp)

      When("nothing changes in the new app")
      val actions = akkaRunner.actions(newApp, currentApp, runners)

      Then("update actions should be created")
      val updateActions = actions.collect {
        case c: CreateOrReplaceAction[_] => c
      }
      val updateDeploymentActions = actions.collect {
        case p: GetAction[Secret] =>
          p.getAction(Some(secret)).asInstanceOf[CreateOrReplaceAction[Deployment]]
      }

      (updateActions.size + updateDeploymentActions.size) mustBe actions.size
    }

    "delete runner resources when new app removes a runner" in {
      Given("a current app, ingress -> egress")
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
      val egress = randomStreamlet().asEgress[Foo].withServerAttribute

      val ingressRef = ingress.ref("ingress")
      val egressRef = egress.ref("egress")
      val bp = Blueprint()
        .define(Vector(ingress, egress))
        .use(ingressRef)
        .use(egressRef)
        .connect(BTopic("foos"), ingressRef.out, egressRef.in)

      val verifiedBlueprint = bp.verified.right.value

      val appId = "thundercat-12345"
      val appVersion = "42-abcdef0"
      val image = "image-1"
      val newAppVersion = appVersion // to compare configmap contents easier.
      val currentApp = App.Cr(
        spec = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths),
        metadata = CloudflowApplicationSpecBuilder.demoMetadata)

      When("the new app removes the egress")
      val newBp =
        bp.disconnect(egressRef.in).remove(egressRef.name)
      val newApp = App.Cr(
        spec =
          CloudflowApplicationSpecBuilder.create(appId, newAppVersion, image, newBp.verified.right.value, agentPaths),
        metadata = CloudflowApplicationSpecBuilder.demoMetadata)
      val actions = akkaRunner.actions(newApp, Some(currentApp), runners)

      Then("delete actions should be created")
      val deleteActions = actions.collect { case d: DeleteAction[_] => d }
      deleteActions.size mustBe 2
    }

    "create new runner resources when a runner is added" in {
      Given("a current app with just an ingress")
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
      val egress = randomStreamlet().asEgress[Foo].withServerAttribute

      val ingressRef = ingress.ref("ingress")
      val bp = Blueprint()
        .define(Vector(ingress, egress))
        .use(ingressRef)
        .connect(BTopic("foos"), ingressRef.out)
      val verifiedBlueprint = bp.verified.right.value

      val appId = "lord-quas-12345"
      val appVersion = "42-abcdef0"
      val image = "image-1"
      val currentApp = App.Cr(
        spec = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths),
        metadata = CloudflowApplicationSpecBuilder.demoMetadata)

      When("the new app adds a runner, ingress -> egress")
      val egressRef = egress.ref("egress")
      val newBp = bp
        .use(egressRef)
        .connect(BTopic("foos"), egressRef.in)
      val newAppVersion = appVersion // to compare configmap contents easier.
      val newApp = App.Cr(
        spec =
          CloudflowApplicationSpecBuilder.create(appId, newAppVersion, image, newBp.verified.right.value, agentPaths),
        metadata = CloudflowApplicationSpecBuilder.demoMetadata)

      Then("create actions for runner resources should be created for the new endpoint")
      val actions = akkaRunner.actions(newApp, Some(currentApp), runners)

      val createActions = actions.collect { case a: CreateOrReplaceAction[_] => a }

      val createDeploymentActions = actions.collect {
        case p: OperatorAction[_, _, _, Try[Secret]] =>
          p.createAction(Success(secret)).asInstanceOf[CreateOrReplaceAction[Deployment]]
        case p: GetAction[Secret] =>
          p.getAction(Some(secret)).asInstanceOf[CreateOrReplaceAction[Deployment]]
      }

      val configMaps = createActions.map(_.resource).collect {
        case configMap: ConfigMap => configMap
      }
      val akkaDeployments = createDeploymentActions.map(_.resource)
      // create and update
      configMaps.size mustBe 2
      akkaDeployments.size mustBe 2

      configMaps.foreach { configMap =>
        assertConfigMap(configMap, newApp.spec, appId, appVersion, ctx)
      }
      akkaDeployments.foreach { deployment =>
        assertAkkaDeployment(deployment, configMaps, newApp.spec, appId, ctx)
      }
    }
  }

  def assertConfigMap(
      configMap: ConfigMap,
      app: App.Spec,
      appId: String,
      appVersion: String,
      ctx: DeploymentContext) = {
    val deployment =
      app.deployments.find(deployment => Name.ofConfigMap(deployment.name) == configMap.getMetadata.getName).value
    (configMap.getData.asScala must contain).key(RunnerConfig.AppConfigFilename)
    val mountedAppConfiguration = ConfigFactory.parseString(configMap.getData.get(RunnerConfig.AppConfigFilename))
    val expectedAppConfiguration =
      ConfigFactory.parseString(RunnerConfig(appId, appVersion, Util.toBlueprint(deployment)).data)
    mountedAppConfiguration mustEqual expectedAppConfiguration
    (configMap.getData.asScala must contain).key(PrometheusConfig.PrometheusConfigFilename)
    val mountedPromConfiguration = configMap.getData.get(PrometheusConfig.PrometheusConfigFilename)
    val expectedPromConfiguration = PrometheusConfig(ctx.akkaRunnerDefaults.prometheusRules).data
    mountedPromConfiguration mustEqual expectedPromConfiguration
  }

  def assertAkkaDeployment(
      deployment: Deployment,
      configMaps: Seq[ConfigMap],
      app: App.Spec,
      appId: String,
      ctx: DeploymentContext) = {
    val streamletDeployment =
      app.deployments
        .find(streamletDeployment => Name.ofPod(streamletDeployment.name) == deployment.getMetadata.getName)
        .value
    val configMap = configMaps.find(cm => Name.ofConfigMap(streamletDeployment.name) == cm.getMetadata.getName).value
    val podSpec = deployment.getSpec.getTemplate.getSpec
    val containers = podSpec.getContainers.asScala
    val volumeMounts = containers.flatMap(_.getVolumeMounts.asScala)

    volumeMounts.size mustBe 3
    forExactly(1, volumeMounts) { volumeMount =>
      volumeMount.getName mustBe configMap.getMetadata.getName
      volumeMount.getReadOnly mustBe true
      volumeMount.getMountPath mustBe Runner.ConfigMapMountPath
    }

    val volumes = podSpec.getVolumes.asScala
    volumes.size mustBe 3
    forExactly(1, volumeMounts)(_.getName mustBe volumes.head.getName)

    volumes.size mustBe 3
    forExactly(1, volumes) { volume =>
      val vol = volume.getConfigMap
      vol mustBe a[ConfigMapVolumeSource]
      vol.getName mustEqual Name.ofConfigMap(streamletDeployment.name)
    }

    if (streamletDeployment.endpoint.isEmpty) {
      deployment.getSpec.getStrategy.getType mustEqual "Recreate"
    } else {
      deployment.getSpec.getStrategy.getType mustEqual "RollingUpdate"
    }

    deployment.getSpec.getReplicas mustBe AkkaRunner.DefaultReplicas

    podSpec.getContainers must have size 1

    val labels = deployment.getSpec.getTemplate.getMetadata.getLabels.asScala
    labels must contain(CloudflowLabels.Name -> Name.ofPod(deployment.getMetadata.getName))
    labels must contain(CloudflowLabels.Component -> CloudflowLabels.StreamletComponent)
    labels must contain(CloudflowLabels.PartOf -> Name.ofLabelValue(appId))
    labels must contain(CloudflowLabels.ManagedBy -> CloudflowLabels.ManagedByCloudflow)

    val container = containers.head

    container.getName mustBe Name.ofPod(deployment.getMetadata.getName)
    container.getImage mustBe streamletDeployment.image

    container.getImagePullPolicy mustBe AkkaRunner.ImagePullPolicy

    val probe = container.getLivenessProbe
    probe.getExec mustBe a[ExecAction]
    probe.getExec.getCommand.asScala.isEmpty mustBe false

    val readinessProbe = container.getReadinessProbe
    readinessProbe.getExec mustBe a[ExecAction]
    readinessProbe.getExec.getCommand.asScala.isEmpty mustBe false

    val runnerDefaults = ctx.akkaRunnerDefaults

    val javaOptsEnvVar =
      new EnvVarBuilder().withName(AkkaRunner.JavaOptsEnvVar).withValue(runnerDefaults.javaOptions).build()
    val promPortEnvVar = new EnvVarBuilder()
      .withName(PrometheusExporterPortEnvVar)
      .withValue(PrometheusConfig.PrometheusJmxExporterPort.toString)
      .build()
    val promRulesPathEnvVar = new EnvVarBuilder()
      .withName(PrometheusExporterRulesPathEnvVar)
      .withValue(PrometheusConfig.prometheusConfigPath(Runner.ConfigMapMountPath))
      .build()

    (container.getEnv.asScala must contain).allOf(javaOptsEnvVar, promPortEnvVar, promRulesPathEnvVar)

    streamletDeployment.endpoint.map { ep =>
      val exposedStreamletPort =
        container.getPorts.asScala.find(_.getContainerPort == ep.containerPort.getOrElse(-1)).value
      exposedStreamletPort.getName mustEqual Name.ofContainerPort(ep.containerPort.getOrElse(-1))
      exposedStreamletPort.getName.length must be <= 15
    }

    container.getPorts.asScala.map(cp => (cp.getContainerPort, cp.getName)) must contain(
      (PrometheusConfig.PrometheusJmxExporterPort, Name.ofContainerPrometheusExporterPort))

    val resourceRequirements = container.getResources
    val resourceConstraints = runnerDefaults.resourceConstraints
    Quantity.getAmountInBytes(resourceRequirements.getRequests.get("cpu")) mustBe Quantity.getAmountInBytes(
      resourceConstraints.cpuRequests)
    Quantity.getAmountInBytes(resourceRequirements.getRequests.get("memory")) mustBe Quantity.getAmountInBytes(
      resourceConstraints.memoryRequests)

    Option(resourceRequirements.getLimits.get("cpu"))
      .map(Quantity.getAmountInBytes) mustBe resourceConstraints.cpuLimits.map(Quantity.getAmountInBytes)
    Option(resourceRequirements.getLimits.get("memory"))
      .map(Quantity.getAmountInBytes) mustBe resourceConstraints.memoryLimits.map(Quantity.getAmountInBytes)
  }
}

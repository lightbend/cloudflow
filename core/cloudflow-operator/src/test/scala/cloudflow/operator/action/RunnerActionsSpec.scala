/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
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

package cloudflow.operator
package action

import org.scalatest.{ ConfigMap ⇒ _, _ }
import com.typesafe.config._
import skuber._
import skuber.apps.v1.Deployment
import cloudflow.blueprint._
import cloudflow.blueprint.deployment._
import BlueprintBuilder._
import cloudflow.operator.runner.AkkaRunner.{ PrometheusExporterPortEnvVar, PrometheusExporterRulesPathEnvVar }
import cloudflow.operator.runner._

class RunnerActionsSpec extends WordSpec with MustMatchers with GivenWhenThen with EitherValues with Inspectors with TestDeploymentContext {

  case class Foo(name: String)
  case class Bar(name: String)
  val namespace  = "ns"
  val agentPaths = Map("prometheus" -> "/app/prometheus/prometheus.jar")

  "RunnerActions" should {
    "create resources for runners when there is no previous application deployment" in {

      Given("no current app and a new app")
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
      val egress  = randomStreamlet().asEgress[Foo].withServerAttribute

      val ingressRef = ingress.ref("ingress")
      val egressRef  = egress.ref("egress")

      val verifiedBlueprint = Blueprint()
        .define(Vector(ingress, egress))
        .use(ingressRef)
        .use(egressRef)
        .connect(ingressRef.out, egressRef.in)
        .verified
        .right
        .value

      val appId      = "def-jux-12345"
      val appVersion = "42-abcdef0"
      val image      = "image-1"

      val currentApp = None
      val newApp     = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths)

      When("runner actions are created from a new app")
      val actions = AkkaRunnerActions(newApp, currentApp, namespace)

      Then("only 'create actions' must be created for every runner")
      val createActions = actions.collect { case c: CreateAction[_] ⇒ c }
      val configMaps = createActions.map(_.resource).collect {
        case configMap: ConfigMap ⇒ configMap
      }
      val akkaDeployments = createActions.map(_.resource).collect {
        case deployment: Deployment ⇒ deployment
      }

      val streamletDeployments = newApp.deployments

      createActions.size mustBe actions.size
      configMaps.size mustBe streamletDeployments.size
      akkaDeployments.size mustBe streamletDeployments.size
      configMaps.foreach { configMap ⇒
        assertConfigMap(configMap, newApp, appId, appVersion, ctx)
      }
      akkaDeployments.foreach { deployment ⇒
        assertAkkaDeployment(deployment, configMaps, newApp, appId, ctx)
      }
    }

    "update when the new applications requires the same runners as the current one" in {
      Given("a current app")
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
      val egress  = randomStreamlet().asEgress[Foo].withServerAttribute

      val ingressRef = ingress.ref("ingress")
      val egressRef  = egress.ref("egress")

      val verifiedBlueprint = Blueprint()
        .define(Vector(ingress, egress))
        .use(ingressRef)
        .use(egressRef)
        .connect(ingressRef.out, egressRef.in)
        .verified
        .right
        .value

      val appId      = "def-jux-12345"
      val appVersion = "42-abcdef0"
      val image      = "image-1"

      val newApp     = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths)
      val currentApp = Some(newApp)

      When("nothing changes in the new app")
      val actions = AkkaRunnerActions(newApp, currentApp, namespace)

      Then("update actions should be created")
      val updateActions = actions.collect { case a: UpdateAction[_] ⇒ a }
      updateActions.size mustBe actions.size
    }

    "delete runner resources when new app removes a runner" in {
      Given("a current app, ingress -> egress")
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
      val egress  = randomStreamlet().asEgress[Foo].withServerAttribute

      val ingressRef = ingress.ref("ingress")
      val egressRef  = egress.ref("egress")
      val bp = Blueprint()
        .define(Vector(ingress, egress))
        .use(ingressRef)
        .use(egressRef)
        .connect(ingressRef.out, egressRef.in)

      val verifiedBlueprint = bp.verified.right.value

      val appId         = "thundercat-12345"
      val appVersion    = "42-abcdef0"
      val image         = "image-1"
      val newAppVersion = appVersion // to compare configmap contents easier.
      val currentApp    = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths)

      When("the new app removes the egress")
      val newBp =
        bp.disconnect(egressRef.in).remove(egressRef.name)
      val newApp  = CloudflowApplicationSpecBuilder.create(appId, newAppVersion, image, newBp.verified.right.value, agentPaths)
      val actions = AkkaRunnerActions(newApp, Some(currentApp), namespace)

      Then("delete actions should be created")
      val deleteActions = actions.collect { case d: DeleteAction[_] ⇒ d }

      val configMaps = deleteActions.map(_.resource).collect {
        case configMap: ConfigMap ⇒ configMap
      }
      val akkaDeployments = deleteActions.map(_.resource).collect {
        case akkaDeployment: Deployment ⇒ akkaDeployment
      }
      configMaps.size mustBe 1
      akkaDeployments.size mustBe 1
      configMaps.foreach { configMap ⇒
        assertConfigMap(configMap, currentApp, appId, appVersion, ctx)
      }
      akkaDeployments.foreach { deployment ⇒
        assertAkkaDeployment(deployment, configMaps, currentApp, appId, ctx)
      }
    }

    "create new runner resources when a runner is added" in {
      Given("a current app with just an ingress")
      val ingress = randomStreamlet().asIngress[Foo].withServerAttribute
      val egress  = randomStreamlet().asEgress[Foo].withServerAttribute

      val ingressRef = ingress.ref("ingress")
      val bp = Blueprint()
        .define(Vector(ingress, egress))
        .use(ingressRef)

      val verifiedBlueprint = bp.verified.right.value

      val appId      = "lord-quas-12345"
      val appVersion = "42-abcdef0"
      val image      = "image-1"
      val currentApp = CloudflowApplicationSpecBuilder.create(appId, appVersion, image, verifiedBlueprint, agentPaths)

      When("the new app adds a runner, ingress -> egress")
      val egressRef = egress.ref("egress")
      val newBp = bp
        .use(egressRef)
        .connect(ingressRef.out, egressRef.in)
      val newAppVersion = appVersion // to compare configmap contents easier.
      val newApp        = CloudflowApplicationSpecBuilder.create(appId, newAppVersion, image, newBp.verified.right.value, agentPaths)

      Then("create actions for runner resources should be created for the new endpoint")
      val actions       = AkkaRunnerActions(newApp, Some(currentApp), namespace)
      val createActions = actions.collect { case a: CreateAction[_] ⇒ a }

      val configMaps = createActions.map(_.resource).collect {
        case configMap: ConfigMap ⇒ configMap
      }
      val akkaDeployments = createActions.map(_.resource).collect {
        case akkaDeployment: Deployment ⇒ akkaDeployment
      }
      configMaps.size mustBe 1
      akkaDeployments.size mustBe 1

      configMaps.foreach { configMap ⇒
        assertConfigMap(configMap, newApp, appId, appVersion, ctx)
      }
      akkaDeployments.foreach { deployment ⇒
        assertAkkaDeployment(deployment, configMaps, newApp, appId, ctx)
      }
    }
  }

  def assertConfigMap(configMap: ConfigMap, app: CloudflowApplication.Spec, appId: String, appVersion: String, ctx: DeploymentContext) = {
    val deployment = app.deployments.find(deployment ⇒ Name.ofConfigMap(deployment.name) == configMap.name).value
    configMap.metadata.namespace mustEqual namespace
    (configMap.data must contain).key(RunnerConfig.AppConfigFilename)
    val mountedAppConfiguration = ConfigFactory.parseString(configMap.data(RunnerConfig.AppConfigFilename))
    val expectedAppConfiguration =
      ConfigFactory.parseString(RunnerConfig(appId, appVersion, deployment, ctx.kafkaContext.bootstrapServers).data)
    mountedAppConfiguration mustEqual expectedAppConfiguration
    (configMap.data must contain).key(PrometheusConfig.PrometheusConfigFilename)
    val mountedPromConfiguration  = configMap.data(PrometheusConfig.PrometheusConfigFilename)
    val expectedPromConfiguration = PrometheusConfig(ctx.akkaRunnerSettings.prometheusRules).data
    mountedPromConfiguration mustEqual expectedPromConfiguration
  }

  def assertAkkaDeployment(deployment: Deployment,
                           configMaps: Seq[ConfigMap],
                           app: CloudflowApplication.Spec,
                           appId: String,
                           ctx: DeploymentContext) = {
    val streamletDeployment = app.deployments.find(streamletDeployment ⇒ Name.ofPod(streamletDeployment.name) == deployment.name).value
    val configMap           = configMaps.find(cm ⇒ Name.ofConfigMap(streamletDeployment.name) == cm.name).value
    val podSpec             = deployment.getPodSpec.value
    val containers          = podSpec.containers
    val volumeMounts        = containers.flatMap(_.volumeMounts)

    volumeMounts.size mustBe 3
    forExactly(1, volumeMounts) { volumeMount ⇒
      volumeMount.name mustBe configMap.metadata.name
      volumeMount.readOnly mustBe true
      volumeMount.mountPath mustBe Runner.ConfigMapMountPath
    }

    val volumes = podSpec.volumes
    volumes.size mustBe 3
    forExactly(1, volumeMounts)(_.name mustBe volumes.head.name)

    volumes.size mustBe 3
    forExactly(1, volumes) { volume ⇒
      val vol = volume.source
      vol mustBe a[Volume.ConfigMapVolumeSource]
      val configMapVolumeSource = vol.asInstanceOf[Volume.ConfigMapVolumeSource]
      configMapVolumeSource.name mustEqual Name.ofConfigMap(streamletDeployment.name)
    }

    if (streamletDeployment.endpoint.isEmpty) {
      deployment.spec.flatMap(_.strategy.map(_._type)).value mustEqual skuber.apps.v1.Deployment.StrategyType.Recreate
    } else {
      deployment.spec.flatMap(_.strategy.map(_._type)).value mustEqual skuber.apps.v1.Deployment.StrategyType.RollingUpdate
    }

    deployment.metadata.namespace mustEqual namespace

    deployment.spec.value.replicas.value mustBe AkkaRunner.NrOfReplicas

    podSpec.containers must have size 1

    val labels = deployment.spec.value.template.metadata.labels
    labels must contain(CloudflowLabels.Name      -> Name.ofPod(deployment.name))
    labels must contain(CloudflowLabels.Component -> CloudflowLabels.StreamletComponent.value)
    labels must contain(CloudflowLabels.PartOf    -> appId)
    labels must contain(CloudflowLabels.ManagedBy -> CloudflowLabels.ManagedByCloudflow)

    val container = containers.head

    container.name mustBe Name.ofPod(deployment.name)
    container.image mustBe streamletDeployment.image

    container.imagePullPolicy mustBe AkkaRunner.ImagePullPolicy

    val probe = container.livenessProbe.value
    probe.action mustBe a[ExecAction]

    val readinessProbe = container.readinessProbe.value
    readinessProbe.action mustBe a[ExecAction]

    val runnerSettings = ctx.akkaRunnerSettings

    val javaOptsEnvVar      = EnvVar(AkkaRunner.JavaOptsEnvVar, EnvVar.StringValue(runnerSettings.javaOptions))
    val promPortEnvVar      = EnvVar(PrometheusExporterPortEnvVar, PrometheusConfig.PrometheusJmxExporterPort.toString)
    val promRulesPathEnvVar = EnvVar(PrometheusExporterRulesPathEnvVar, PrometheusConfig.prometheusConfigPath(Runner.ConfigMapMountPath))

    (container.env must contain).allOf(javaOptsEnvVar, promPortEnvVar, promRulesPathEnvVar)

    streamletDeployment.endpoint.map { ep ⇒
      val exposedStreamletPort = container.ports.find(_.containerPort == ep.containerPort).value
      exposedStreamletPort.name mustEqual Name.ofContainerPort(ep.containerPort)
      exposedStreamletPort.name.length must be <= 15
    }

    container.ports must contain(Container.Port(PrometheusConfig.PrometheusJmxExporterPort, name = Name.ofContainerPrometheusExporterPort))

    val resourceRequirements = container.resources.value
    val resourceConstraints  = runnerSettings.resourceConstraints
    resourceRequirements.requests must contain(Resource.cpu    -> resourceConstraints.cpuRequests)
    resourceRequirements.requests must contain(Resource.memory -> resourceConstraints.memoryRequests)

    resourceRequirements.limits.get(Resource.cpu) mustBe resourceConstraints.cpuLimits
    resourceRequirements.limits.get(Resource.memory) mustBe resourceConstraints.memoryLimits
  }
}

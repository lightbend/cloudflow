/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cli

import java.io.{ File, FileInputStream }
import scala.annotation.nowarn
import akka.cli.cloudflow._
import akka.cli.cloudflow.commands
import akka.cli.cloudflow.commands.{ format, Command }
import io.fabric8.kubernetes.api.model.{ NamespaceBuilder, ObjectMetaBuilder }
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.utils.Serialization

// Main to cover most of the possible codepaths for generating the GraalVM configuration
object CodepathCoverageMain extends App {

  OptionsParser(args)

  val logger: CliLogger = new CliLogger(Some("trace"))

  Setup.init()

  // Cluster preparation
  val preparationClient = new DefaultKubernetesClient()
  preparationClient
    .namespaces()
    .createOrReplace(
      new NamespaceBuilder().withMetadata(new ObjectMetaBuilder().withName("swiss-knife").build()).build())

  val flinkPvc = preparationClient.load(new FileInputStream("./src/main/resources/flink-pvc.yaml")).get()
  try {
    preparationClient.resourceList(flinkPvc).inNamespace("swiss-knife").delete()
  } catch {
    case ex: Throwable =>
      ex.printStackTrace()
  }
  preparationClient.resourceList(flinkPvc).inNamespace("swiss-knife").createOrReplace()
  val sparkPvc = preparationClient.load(new FileInputStream("./src/main/resources/spark-pvc.yaml")).get()
  try {
    preparationClient.resourceList(sparkPvc).inNamespace("swiss-knife").delete()
  } catch {
    case ex: Throwable =>
      ex.printStackTrace()
  }
  preparationClient.resourceList(sparkPvc).inNamespace("swiss-knife").createOrReplace()

  val microservicesCRD =
    preparationClient.load(new FileInputStream("./src/main/resources/akka-microservices-crd.yml")).get()
  preparationClient.resourceList(microservicesCRD).createOrReplace()

  preparationClient.close()

  val cli = new Cli(None, (_, l) => Cli.defaultKubeClient(None, l))(logger) {
    @nowarn def transform[T](cmd: Command[T], res: T): T = res

    @nowarn def handleError[T](cmd: Command[T], ex: Throwable): Unit = ()
  }

  val printingCli = new PrintingCli(None)(logger)

  new PrintingCli(None)(logger)

  val version = cli.run(commands.Version())
  version.get.render(format.Classic)
  version.get.render(format.Json)
  version.get.render(format.Yaml)

  cli.run(
    commands
      .Deploy(crFile = new File("../cloudflow-it/swiss-knife/target/swiss-knife.json"), noRegistryCredentials = true))

  val list = commands.List()
  def exists() = {
    val res = cli.run(list)
    if (res.isFailure) {
      println(res.failed.get.getMessage)
      false
    } else {
      res.get.render(format.Classic)
      res.get.render(format.Json)
      res.get.render(format.Yaml)
      res.get.summaries.nonEmpty
    }
  }

  while (!exists()) {
    Thread.sleep(1000)
    println("Waiting for swiss knife to be deployed")
  }

  printingCli.run(list)

  val status = commands.Status(cloudflowApp = "swiss-knife")
  def running() = {
    val res = cli.run(status)
    if (res.isFailure) {
      println(res.failed.get.getMessage)
      false
    } else {
      res.get.render(format.Classic)
      res.get.render(format.Json)
      res.get.render(format.Yaml)
      res.get.status.status == "Running"
    }
  }

  while (!running()) {
    Thread.sleep(1000)
    println("Waiting for swiss knife to be running")
  }

  printingCli.run(status)

  cli.run(
    commands
      .UpdateCredentials(cloudflowApp = "swiss-knife", dockerRegistry = "example.io", username = "u", password = "p"))
  cli.run(
    commands
      .UpdateCredentials(cloudflowApp = "swiss-knife", dockerRegistry = "example2.io", username = "u", password = "p"))

  cli.run(commands.Scale(cloudflowApp = "swiss-knife", scales = Map("akka-process" -> 5)))
  cli.run(commands.Scale(cloudflowApp = "swiss-knife", scales = Map("akka-process" -> 2)))

  cli.run(
    commands.Configure(
      cloudflowApp = "swiss-knife",
      configKeys =
        Map("cloudflow.streamlets.akka-process.kubernetes.pods.pod.volumes.default.pvc.name" -> "cloudflow-spark")))

  printingCli.run(commands.Configuration(cloudflowApp = "swiss-knife"))
  val conf = cli.run(commands.Configuration(cloudflowApp = "swiss-knife"))
  conf.get.render(format.Classic)
  conf.get.render(format.Json)
  conf.get.render(format.Yaml)

  cli.run(commands.Undeploy(cloudflowApp = "swiss-knife"))

  Thread.sleep(5000)

  // Manually targeting exceptions:

  Serialization
    .jsonMapper()
    .readValue("{}", classOf[io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionList])

  Serialization
    .jsonMapper()
    .readValue("{}", classOf[io.fabric8.kubernetes.api.model.EndpointsList])

  Serialization
    .jsonMapper()
    .readValue("{}", classOf[cloudflow.runner.config.Topic])
  Serialization
    .jsonMapper()
    .readValue("{}", classOf[cloudflow.runner.config.VolumeMount])
  Serialization
    .jsonMapper()
    .readValue("{}", classOf[cloudflow.runner.config.StreamletContext])
  Serialization
    .jsonMapper()
    .readValue("{}", classOf[cloudflow.runner.config.Streamlet])
  Serialization
    .jsonMapper()
    .readValue("{}", classOf[cloudflow.runner.config.Runner])
  Serialization
    .jsonMapper()
    .readValue("{}", classOf[cloudflow.runner.config.Cloudflow])
  Serialization
    .jsonMapper()
    .readValue("{}", classOf[cloudflow.runner.config.CloudflowRoot])

  // EKS auth configuration
  Serialization
    .jsonMapper()
    .readValue("{}", Class.forName("io.fabric8.kubernetes.client.Config$ExecCredential"))
  Serialization
    .jsonMapper()
    .readValue("{}", Class.forName("io.fabric8.kubernetes.client.Config$ExecCredentialSpec"))
  Serialization
    .jsonMapper()
    .readValue("{}", Class.forName("io.fabric8.kubernetes.client.Config$ExecCredentialStatus"))

  System.exit(0)
}

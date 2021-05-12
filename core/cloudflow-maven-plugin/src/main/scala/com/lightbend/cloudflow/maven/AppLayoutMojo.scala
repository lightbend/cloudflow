package com.lightbend.cloudflow.maven

import cloudflow.blueprint.deployment._
import com.github.mdr.ascii.graph.Graph
import com.github.mdr.ascii.layout.GraphLayout
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.{ AbstractMojo, BuildPluginManager }
import org.apache.maven.plugins.annotations._
import org.apache.maven.project.MavenProject

import scala.collection.JavaConverters._

@Mojo(
  name = "app-layout",
  aggregator = false,
  requiresDependencyResolution = ResolutionScope.COMPILE,
  requiresDependencyCollection = ResolutionScope.COMPILE,
  defaultPhase = LifecyclePhase.PACKAGE)
class AppLayoutMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  var mavenProject: MavenProject = _

  @Parameter(defaultValue = "${session}", required = true, readonly = true)
  var mavenSession: MavenSession = _

  @Component
  var pluginManager: BuildPluginManager = _

  private def resolveConnections(appDescriptor: ApplicationDescriptor): List[(String, String)] = {
    def topicFormat(topic: String): String =
      s"[$topic]"
    val streamletIOResolver = appDescriptor.streamlets.map { st =>
      val inlets = st.descriptor.inlets.map(_.name)
      val outlets = st.descriptor.outlets.map(_.name)
      val inOut = inlets.map(name => name -> "inlet") ++ outlets.map(name => name -> "outlet")
      st.name -> inOut.toMap
    }.toMap

    appDescriptor.deployments.flatMap { deployment =>
      val streamlet = deployment.streamletName
      val inletOutlets = streamletIOResolver(streamlet)
      val topicsOtherStreamlet = deployment.portMappings.toSeq.map {
        case (port, topic) =>
          val formattedTopic = topicFormat(topic.name)
          val io = inletOutlets(port)
          if (io == "inlet") {
            // TODO verify this
            s"$formattedTopic" -> s"${deployment.streamletName}"
          } else {
            // TODO verify this
            s"${deployment.streamletName}" -> s"$formattedTopic"
          }
      }
      topicsOtherStreamlet
    }.toList
  }

  private def getAppLayout(connections: List[(String, String)]): String = {
    val vertices = connections.flatMap { case (a, b) => Seq(a, b) }.toSet
    val graph = Graph(vertices = vertices, edges = connections)
    GraphLayout.renderGraph(graph)
  }

  def execute(): Unit = {
    val topLevel = mavenSession.getTopLevelProject
    val projectId = topLevel.getName
    val version = topLevel.getVersion

    val allProjects = mavenSession.getAllProjects.asScala

    if (allProjects.last == mavenProject) {

      val cr = CloudflowAggregator.getCR(
        CloudflowAggregator
          .generateLocalCR(projectId = projectId, version = version, allProjects = allProjects, log = getLog()))

      val res = getAppLayout(resolveConnections(cr.spec))

      getLog.info("App Layout:")
      getLog.info(res)
    }
  }

}

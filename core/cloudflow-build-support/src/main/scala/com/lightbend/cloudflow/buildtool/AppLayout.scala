package com.lightbend.cloudflow.buildtool

import cloudflow.blueprint.deployment._
import com.github.mdr.ascii.graph.Graph
import com.github.mdr.ascii.layout.GraphLayout

import scala.collection.JavaConverters._

object AppLayout {

  def resolveConnections(appDescriptor: ApplicationDescriptor): List[(String, String)] = {
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

  def getAppLayout(connections: List[(String, String)]): String = {
    val vertices = connections.flatMap { case (a, b) => Seq(a, b) }.toSet
    val graph = Graph(vertices = vertices, edges = connections)
    GraphLayout.renderGraph(graph)
  }

}

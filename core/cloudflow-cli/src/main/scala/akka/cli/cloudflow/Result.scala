/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow

import java.text.SimpleDateFormat
import scala.jdk.CollectionConverters._
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import de.vandermeer.asciitable._
import de.vandermeer.asciithemes.a7.A7_Grids
import de.vandermeer.skb.interfaces.transformers.textformat.TextAlignment
import akka.cli.cloudflow.commands.format
import akka.cli.cloudflow.models._
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature
import com.typesafe.config.{ Config, ConfigRenderOptions }

trait Result {
  def render(fmt: format.Format): String
}

@JsonCreator
final case class VersionResult(version: String) extends Result {

  def render(fmt: format.Format): String = {
    fmt match {
      case format.Classic => version
      case format.Table => {
        val table = new AsciiTable()

        table.setTextAlignment(TextAlignment.LEFT)

        table.addRule()
        table.addRow("VERSION", version)
        table.addRule()

        table.setPaddingLeftRight(1)
        table.getRenderer.setCWC(new CWC_LongestWordMin(3))
        table.getContext.setGrid(A7_Grids.minusBarPlus())

        table.render()
      }
      case format.Json => JsonHelper.objectMapper.writeValueAsString(this)
      case format.Yaml => YamlHelper.objectMapper.writeValueAsString(this)
    }
  }
}

@JsonCreator
final case class ListResult(summaries: List[CRSummary]) extends Result {
  private val headers = Seq("NAME", "NAMESPACE", "VERSION", "CREATION-TIME")

  def render(fmt: format.Format): String = {
    fmt match {
      case format.Classic => {
        val body: Seq[Seq[String]] = summaries.map { s =>
          Seq(s.name, s.namespace, s.version, ClassicHelper.dateString(s.creationTime))
        }
        ClassicHelper.format(headers +: body)
      }
      case format.Table => renderFancy()
      case format.Json  => JsonHelper.objectMapper.writeValueAsString(this)
      case format.Yaml  => YamlHelper.objectMapper.writeValueAsString(this)
    }
  }

  private def renderFancy() = {
    val table = new AsciiTable()

    table.addRule()
    table.addRow(headers: _*)
    table.addRule()

    summaries.foreach { s =>
      table.addRow(s.name, s.namespace, s.version, s.creationTime)
    }

    table.addRule()
    table.setPaddingLeftRight(1)
    table.setTextAlignment(TextAlignment.LEFT)

    table.getRenderer.setCWC(new CWC_LongestWordMin(3))
    table.getContext.setGrid(A7_Grids.minusBarPlus())

    table.render()
  }
}

@JsonCreator
final case class StatusResult(status: ApplicationStatus) extends Result {
  private val endpointHeaders = Seq("STREAMLET", "ENDPOINT")
  private val streamletHeaders =
    Seq("STREAMLET", "POD", "READY", "STATUS", "RESTARTS")

  def render(fmt: format.Format): String = {
    fmt match {
      case format.Classic => renderClassic()
      case format.Table   => renderFancy()
      case format.Json    => JsonHelper.objectMapper.writeValueAsString(this)
      case format.Yaml    => YamlHelper.objectMapper.writeValueAsString(this)
    }
  }

  private def renderClassic(): String = {
    val summary = {
      Seq(
        s"Name:             ${status.summary.name}",
        s"Namespace:        ${status.summary.namespace}",
        s"Version:          ${status.summary.version}",
        s"Created:          ${ClassicHelper.dateString(status.summary.creationTime)}",
        s"Status:           ${status.status}").mkString("", "\n", "")
    }

    val endpointsList = {
      if (status.endpointsStatuses.nonEmpty) {
        val body: Seq[Seq[String]] =
          status.endpointsStatuses.sortBy(_.name).map { endpointStatus =>
            Seq(endpointStatus.name, endpointStatus.url.toString)
          }

        ClassicHelper.format((endpointHeaders +: body))
      } else { "" }
    }

    val streamletList = {
      if (status.streamletsStatuses.nonEmpty) {
        val body: Seq[Seq[String]] = status.streamletsStatuses
          .sortBy(_.name)
          .map { streamletStatus =>
            if (streamletStatus.podsStatuses.isEmpty) {
              Seq(Seq(streamletStatus.name, "", "0/0", "Missing", "0"))
            } else {
              streamletStatus.podsStatuses.map { podStatus =>
                Seq(
                  streamletStatus.name,
                  podStatus.name,
                  s"${podStatus.ready.ready}/${podStatus.ready.total}",
                  podStatus.status,
                  s"${podStatus.restarts}")
              }
            }
          }
          .flatten

        ClassicHelper.format(streamletHeaders +: body)
      } else {
        "\n"
      }
    }

    summary + "\n" + endpointsList + "\n" + streamletList
  }

  private def renderFancy() = {
    val appTable = new AsciiTable()

    def centerLast(row: AT_Row) = {
      row.getCells.getLast.getContext.setTextAlignment(TextAlignment.CENTER)
    }

    appTable.setTextAlignment(TextAlignment.LEFT)

    appTable.addRule()
    centerLast(appTable.addRow("Name:", status.summary.name))
    centerLast(appTable.addRow("Namespace:", status.summary.namespace))
    centerLast(appTable.addRow("Version:", status.summary.version))
    centerLast(appTable.addRow("Created:", status.summary.creationTime))
    centerLast(appTable.addRow("Status:", status.status))
    appTable.addRule()

    appTable.setPaddingLeftRight(1)
    appTable.getRenderer.setCWC(new CWC_LongestWordMin(3))
    appTable.getContext.setGrid(A7_Grids.minusBarPlus())

    val endpointsTable = {
      if (status.endpointsStatuses.nonEmpty) {
        val table = new AsciiTable()

        table.setTextAlignment(TextAlignment.LEFT)

        table.addRule()
        table.addRow(endpointHeaders: _*)
        table.addRule()

        status.endpointsStatuses.sortBy(_.name).foreach { endpointStatus =>
          table.addRow(endpointStatus.name, endpointStatus.url.toString)
        }
        table.addRule()

        table.setPaddingLeftRight(1)
        table.getRenderer.setCWC(new CWC_LongestWordMin(3))
        table.getContext.setGrid(A7_Grids.minusBarPlus())

        table.render()
      } else { "\n" }
    }
    val streamletsTable = {
      if (status.streamletsStatuses.nonEmpty) {
        val table = new AsciiTable()

        def centerCols(row: AT_Row, cols: Int*) = {
          cols.foreach { i =>
            row.getCells.get(i).getContext.setTextAlignment(TextAlignment.CENTER)
          }
        }

        table.setTextAlignment(TextAlignment.LEFT)

        table.addRule()
        table.addRow(streamletHeaders: _*)
        table.addRule()

        status.streamletsStatuses.sortBy(_.name).foreach { streamletStatus =>
          if (streamletStatus.podsStatuses.isEmpty) {
            centerCols(table.addRow(streamletStatus.name, "", "0/0", "Missing", "0"), 2, 3, 4)
          } else {
            streamletStatus.podsStatuses.sortBy(_.name).foreach { podStatus =>
              centerCols(
                table.addRow(
                  streamletStatus.name,
                  podStatus.name,
                  s"${podStatus.ready.ready}/${podStatus.ready.total}",
                  podStatus.status,
                  s"${podStatus.restarts}"),
                2,
                3,
                4)
            }
          }
        }
        table.addRule()

        table.setPaddingLeftRight(1)
        table.getRenderer.setCWC(new CWC_LongestWordMin(3))
        table.getContext.setGrid(A7_Grids.minusBarPlus())

        table.render()
      } else {
        "\n"
      }
    }

    appTable.render() + endpointsTable + streamletsTable
  }
}

@JsonCreator
final case class ConfigurationResult(config: Config) extends Result {
  private val headers = Seq("KEY", "VALUE")

  def getKV() = {
    config
      .entrySet()
      .asScala
      .toSeq
      .sortBy { _.getKey }
  }

  def render(fmt: format.Format): String = {
    fmt match {
      case format.Classic =>
        ClassicHelper.format(headers +: getKV().map { e => Seq(e.getKey.toString, e.getValue.unwrapped().toString) })
      case format.Table => {
        val table = new AsciiTable()

        table.setTextAlignment(TextAlignment.LEFT)

        table.addRule()
        table.addRow(headers: _*)
        table.addRule()
        getKV().map { entry =>
          table.addRow(entry.getKey, entry.getValue.unwrapped())
        }
        table.addRule()

        table.setPaddingLeftRight(1)
        table.getRenderer.setCWC(new CWC_LongestWordMin(3))
        table.getContext.setGrid(A7_Grids.minusBarPlus())

        table.render()
      }
      case format.Json => config.root().render(ConfigRenderOptions.concise())
      case format.Yaml =>
        val json = JsonHelper.objectMapper.readTree(config.root().render(ConfigRenderOptions.concise()))
        YamlHelper.objectMapper.writeValueAsString(json)
    }
  }
}

@JsonCreator
final case class DeployResult() extends Result {
  def render(fmt: format.Format): String = ""
}

@JsonCreator
final case class UndeployResult() extends Result {
  def render(fmt: format.Format): String = ""
}

@JsonCreator
final case class UpdateCredentialsResult() extends Result {
  def render(fmt: format.Format): String = ""
}

@JsonCreator
final case class ScaleResult() extends Result {
  def render(fmt: format.Format): String = ""
}

@JsonCreator
final case class ConfigureResult() extends Result {
  def render(fmt: format.Format): String = ""
}

object JsonHelper {
  val objectMapper = new ObjectMapper().registerModule(new DefaultScalaModule())
}

object YamlHelper {
  val objectMapper =
    new ObjectMapper(new YAMLFactory().disable(Feature.WRITE_DOC_START_MARKER)).registerModule(new DefaultScalaModule())
}

object ClassicHelper {

  private val inDateFmtStr = io.fabric8.kubernetes.client.VersionInfo.VersionKeys.BUILD_DATE_FORMAT
  private val outDateFmtStr = "yyyy-MM-dd HH:mm:ss Z z"

  def dateString(str: String): String = {
    val inFmt = new SimpleDateFormat(inDateFmtStr)

    val date = inFmt.parse(str)

    val outFmt = new SimpleDateFormat(outDateFmtStr)

    outFmt.format(date)
  }

  // NOTE: this method comes with no checks on boundaries
  protected[cloudflow] def format(rows: Seq[Seq[String]]): String = {
    val columns =
      for (i <- 0.until(rows.head.size)) yield {
        rows.map(_(i))
      }

    val formattedColumns = for (i <- 0.until(columns.size)) yield {
      formatColumn(columns(i), (i == columns.size - 1))
    }

    val res = for (i <- 0.until(columns.head.size)) yield {
      formattedColumns.map(_(i))
    }

    res
      .map(_.mkString("", "", ""))
      .mkString("", "\n", "")
  }

  private def formatColumn(elems: Seq[String], last: Boolean) = {
    val maxStringSize = elems.map(_.size).sorted.lastOption.getOrElse(0)

    val padValue =
      if (maxStringSize > 18) maxStringSize + 1
      else 18

    if (last) {
      elems
    } else {
      elems.map(_.padTo(padValue, ' '))
    }
  }
}

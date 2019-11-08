package sensordata

import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._

class ValidMetricLogger extends AkkaStreamlet {
  val inlet = AvroInlet[Metric]("in")
  val shape = StreamletShape.withInlets(inlet)

  val LogLevel = RegExpConfigParameter(
    "log-level",
    "Provide one of the following log levels, debug, info, warning or error",
    "^debug|info|warning|error$",
    Some("debug")
  )

  val MsgPrefix = StringConfigParameter(
    "msg-prefix",
    "Provide a prefix for the log lines",
    Some("valid-logger"))

  override def configParameters = Vector(LogLevel, MsgPrefix)

  override def createLogic = new RunnableGraphStreamletLogic() {
    val logF: String ⇒ Unit = streamletConfig.getString(LogLevel.key).toLowerCase match {
      case "debug"   ⇒ system.log.debug _
      case "info"    ⇒ system.log.info _
      case "warning" ⇒ system.log.warning _
      case "error"   ⇒ system.log.error _
    }

    val msgPrefix = streamletConfig.getString(MsgPrefix.key)

    def log(metric: Metric) = {
      logF(s"$msgPrefix $metric")
    }

    def flow = {
      FlowWithOffsetContext[Metric]
        .map { validMetric ⇒
          log(validMetric)
          validMetric
        }
    }

    def runnableGraph = {
      sourceWithOffsetContext(inlet).via(flow).to(sinkWithOffsetContext)
    }
  }
}

/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow

import buildinfo.BuildInfo
import wvlet.log.LogFormatter.{ appendStackTrace, highlightLog, withColor }
import wvlet.log.LogTimestampFormatter.formatTimestamp
import wvlet.log.{ LogFormatter, LogLevel, LogRecord, Logger }

object CliLogger {
  val DefaultLevel = "off"
}

class CliLogger(optLevel: Option[String]) {
  val level = optLevel.getOrElse(CliLogger.DefaultLevel)

  private object NoLocSourceCodeLogFormatter extends LogFormatter {
    override def formatLog(r: LogRecord): String = {

      val logTag = highlightLog(r.level, r.level.name)
      val log =
        f"${withColor(Console.BLUE, formatTimestamp(r.getMillis))} ${logTag}%14s [${withColor(
          Console.WHITE,
          r.leafLoggerName)}] ${highlightLog(r.level, r.getMessage)}"
      appendStackTrace(log, r)
    }
  }

  private val logger = {
    Logger.init
    val _logger = Logger(BuildInfo.name)
    _logger.setFormatter(NoLocSourceCodeLogFormatter)
    _logger.setLogLevel(LogLevel(level))
    _logger
  }

  def trace(msg: => String) = logger.trace(msg)
  def info(msg: => String) = logger.info(msg)
  def warn(msg: => String) = logger.warn(msg)
  def warn(msg: => String, ex: => Throwable) = logger.warn(msg, ex)
  def error(msg: => String) = logger.error(msg)
  def error(msg: => String, ex: => Throwable) = logger.error(msg, ex)

  def close() = {
    logger.getHandlers.foreach { handler =>
      handler.flush()
      handler.close()
    }
  }
}

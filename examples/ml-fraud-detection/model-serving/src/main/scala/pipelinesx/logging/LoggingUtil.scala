package pipelinesx.logging

import org.slf4j.{ Logger ⇒ SLogger, LoggerFactory ⇒ SLoggerFactory }
import akka.actor.ActorSystem
import akka.event.Logging.{ LogLevel ⇒ ALogLevel, InfoLevel ⇒ AInfoLevel }
import java.io.PrintStream

/**
 * Wrapper around logging to support SLF4J, Akka Logging, and stdout. The _only_
 * reason this exists is to make it easier to capture logged output during tests
 * for verification or to suppress the output (after all other attempts at this
 * failed...).
 */
trait Logger {
  /** See implementations for details on behavior. */
  def log(msg: String): Unit
  def debug(msg: String): Unit
  def info(msg: String): Unit
  def warn(msg: String): Unit
  def error(msg: String): Unit
  /** Convenience method to log a Throwable. */
  def throwable(msg: String, th: Throwable): Unit =
    LoggingUtil.logThrowable(msg + ". Failed with exception: ", th)(line ⇒ error(line))
}

final class MutableLogger(var logger: Logger) extends Logger {
  def setLogger(l: Logger): Unit = logger = l

  def log(msg: String): Unit = logger.log(msg)
  def debug(msg: String): Unit = logger.debug(msg)
  def info(msg: String): Unit = logger.info(msg)
  def warn(msg: String): Unit = logger.warn(msg)
  def error(msg: String): Unit = logger.error(msg)
}

final case class SLF4JLogger(logger: SLogger) extends Logger {
  /** Since there is no generic "log" method in the SLF4J logger, this method logs as INFO. */
  def log(msg: String): Unit = info(msg)
  def debug(msg: String): Unit = logger.debug(msg)
  def info(msg: String): Unit = logger.info(msg)
  def warn(msg: String): Unit = logger.warn(msg)
  def error(msg: String): Unit = logger.error(msg)
}

/**
 * Akka Logger wrapper.
 * @param system the ActorSystem for Akka
 * @param defaultLevel the LogLevel value used if you call the "log(...)" method.
 */
final case class AkkaLogger(system: ActorSystem, defaultLevel: ALogLevel = AInfoLevel) extends Logger {
  def log(msg: String): Unit = system.log.log(defaultLevel, msg)
  def debug(msg: String): Unit = system.log.debug(msg)
  def info(msg: String): Unit = system.log.info(msg)
  def warn(msg: String): Unit = system.log.warning(msg)
  def error(msg: String): Unit = system.log.error(msg)
}

/** Writes debug and info messages to stdout, warn and error messages to stderr */
final case class StdoutStderrLogger(clazz: Class[_]) extends Logger {
  val className = clazz.getName
  /** Treats the message as an INFO message. */
  def log(msg: String): Unit = info(msg)
  def debug(msg: String): Unit = StdoutStderrLogger.write(Console.out, "DEBUG", className, msg)
  def info(msg: String): Unit = StdoutStderrLogger.write(Console.out, "INFO", className, msg)
  def warn(msg: String): Unit = StdoutStderrLogger.write(Console.err, "WARN", className, msg)
  def error(msg: String): Unit = StdoutStderrLogger.write(Console.err, "ERROR", className, msg)
}
object StdoutStderrLogger {
  def write(out: PrintStream, level: String, className: String, message: String): Unit =
    out.println(makeMessage(level, className, message))

  def makeMessage(level: String, className: String, message: String): String =
    s"[$level] ($className): $message"
}

object LoggingUtil {
  // Global hook to configure which logger API is used.
  var useSLF4J: Boolean = false

  /**
   * Always call this method to create the default SL4J logger, wrapped in a [[MutableLogger]].
   * To override in a test to use stdout, call `mylogger.setLogger(StdoutStderrLogger)`.
   */
  def getLogger[T](clazz: Class[T]): MutableLogger = {
    // val which = if (useSLF4J) "SLF4J" else "stdout/stderr"
    // StdoutStderrLogger.write(Console.out, "INFO", this.getClass.getName, s"By default, using $which logger")

    if (useSLF4J) new MutableLogger(SLF4JLogger(SLoggerFactory.getLogger(clazz)))
    else new MutableLogger(StdoutStderrLogger(clazz))
  }

  /**
   * Helper that nicely formats and logs an exception, including its stack trace
   * and all the causes and their stack traces!
   */
  def logThrowable(msg: String, th: Throwable)(perLineLogger: String ⇒ Unit): Unit = {
    perLineLogger(msg)
    throwableToStrings(th).foreach(perLineLogger)
  }

  /**
   * Helper that nicely formats an exception, its stack trace and all the causes
   * and their stack traces! calls {@link throwableToStrings} to construct an
   * array, which this method concatenates separated by the supplied delimiter.
   * @param th the Throwable
   * @param delimiter insert between each line.
   */
  def throwableToString(th: Throwable, delimiter: String = "\n"): String =
    throwableToStrings(th).mkString(delimiter)

  /**
   * Helper that nicely formats and logs an exception, including its stack trace
   * and all the causes and their stack traces!
   */
  def throwableToStrings(th: Throwable): Vector[String] = {
    var vect = Vector.empty[String]
    var throwable = th
    vect = vect :+ (s"$throwable: " + formatStackTrace(throwable))
    throwable = throwable.getCause()
    while (throwable != null) {
      vect = vect :+ "\nCaused by: "
      vect = vect :+ (s"$throwable: " + formatStackTrace(throwable))
      throwable = throwable.getCause()
    }
    vect
  }

  private def formatStackTrace(th: Throwable): String =
    th.getStackTrace().mkString("\n  ", "\n  ", "\n")

}

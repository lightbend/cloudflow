/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

// Extracted from sbt:
// https://github.com/sbt/sbt
/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package cloudflow.maven

import java.io.File
import java.lang.ProcessBuilder.Redirect
import scala.sys.process.{ Process, ProcessLogger }
import OutputStrategy._
import org.apache.maven.plugin.logging.Log

import java.lang.{ ProcessBuilder => JProcessBuilder }
import java.util.Locale

/**
 * Represents a command that can be forked.
 *
 * @param commandName The java-like binary to fork.  This is expected to exist in bin/ of the Java home directory.
 * @param runnerClass If Some, this will be prepended to the `arguments` passed to the `apply` or `fork` methods.
 */
final class Fork(val commandName: String, val runnerClass: Option[String]) {

  /**
   * Forks the configured process, waits for it to complete, and returns the exit code.
   * The command executed is the `commandName` defined for this Fork instance.
   * It is configured according to `config`.
   * If `runnerClass` is defined for this Fork instance, it is prepended to `arguments` to define the arguments passed to the forked command.
   */
  def apply(config: ForkOptions, arguments: Seq[String]): Int = fork(config, arguments).exitValue()

  /**
   * Forks the configured process and returns a `Process` that can be used to wait for completion or to terminate the forked process.
   * The command executed is the `commandName` defined for this Fork instance.
   * It is configured according to `config`.
   * If `runnerClass` is defined for this Fork instance, it is prepended to `arguments` to define the arguments passed to the forked command.
   */
  def fork(config: ForkOptions, arguments: Seq[String]): Process = {
    import config.{ envVars => env, _ }
    val executable = Fork.javaCommand(javaHome, commandName).getAbsolutePath
    val preOptions = makeOptions(runJVMOptions, bootJars, arguments)
    val (classpathEnv, options) = Fork.fitClasspath(preOptions)
    val command = executable +: options

    val environment: List[(String, String)] = env.toList ++
      classpathEnv.map { value =>
        Fork.ClasspathEnvKey -> value
      }
    val jpb = new JProcessBuilder(command.toArray: _*)
    workingDirectory.foreach(jpb.directory(_))
    environment.foreach { case (k, v) => jpb.environment.put(k, v) }
    if (connectInput) {
      jpb.redirectInput(Redirect.INHERIT)
      ()
    }
    val process = Process(jpb)

    outputStrategy.getOrElse(StdoutOutput: OutputStrategy) match {
      case StdoutOutput        => process.run(connectInput = false)
      case out: BufferedOutput => { process.run(ProcessLogger((s) => out.logger.info(s)), connectInput = false) }
      case out: LoggedOutput   => process.run(ProcessLogger(out.file), connectInput = false)
      case out: CustomOutput   => (process #> out.output).run(connectInput = false)
    }
  }
  private[this] def makeOptions(
      jvmOptions: Seq[String],
      bootJars: Iterable[File],
      arguments: Seq[String]): Seq[String] = {
    val boot =
      if (bootJars.isEmpty) Seq.empty[String]
      else
        Seq("-Xbootclasspath/a:" + bootJars.map(_.getAbsolutePath).mkString(File.pathSeparator))
    jvmOptions ++ boot.toList ++ runnerClass.toList ++ arguments
  }
}
object Fork {
  private val ScalacMainClass = "scala.tools.nsc.Main"
  private val ScalaMainClass = "scala.tools.nsc.MainGenericRunner"
  private val JavaCommandName = "java"

  val java = new Fork(JavaCommandName, None)
  val javac = new Fork("javac", None)
  val scala = new Fork(JavaCommandName, Some(ScalaMainClass))
  val scalac = new Fork(JavaCommandName, Some(ScalacMainClass))

  private val ClasspathEnvKey = "CLASSPATH"
  private[this] val ClasspathOptionLong = "-classpath"
  private[this] val ClasspathOptionShort = "-cp"
  private[this] def isClasspathOption(s: String) =
    s == ClasspathOptionLong || s == ClasspathOptionShort

  /** Maximum length of classpath string before passing the classpath in an environment variable instead of an option. */
  private[this] val MaxConcatenatedOptionLength = 5000

  lazy val isWindows: Boolean =
    System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")

  private def fitClasspath(options: Seq[String]): (Option[String], Seq[String]) =
    if (isWindows && optionsTooLong(options))
      convertClasspathToEnv(options)
    else
      (None, options)
  private[this] def optionsTooLong(options: Seq[String]): Boolean =
    options.mkString(" ").length > MaxConcatenatedOptionLength

  private[this] def convertClasspathToEnv(options: Seq[String]): (Option[String], Seq[String]) = {
    val (preCP, cpAndPost) = options.span(opt => !isClasspathOption(opt))
    val postCP = cpAndPost.drop(2)
    val classpathOption = cpAndPost.drop(1).headOption
    val newOptions = if (classpathOption.isDefined) preCP ++ postCP else options
    (classpathOption, newOptions)
  }

  private def javaCommand(javaHome: Option[File], name: String): File = {
    val home = javaHome.getOrElse(new File(System.getProperty("java.home")))
    new File(new File(home, "bin"), name)
  }
}

/**
 * This code is generated using [[https://www.scala-sbt.org/contraband/ sbt-contraband]].
 */
// DO NOT EDIT MANUALLY
/**
 * Configures forking.
 * @param javaHome The Java installation to use.  If not defined, the Java home for the current process is used.
 * @param outputStrategy Configures the forked standard output and error streams.
                         If not defined, StdoutOutput is used, which maps the forked output to the output of
                         this process and the forked error to the error stream of the forking process.
 * @param bootJars The Vector of jars to put on the forked boot classpath.  By default, this is empty.
 * @param workingDirectory The directory to use as the working directory for the forked process.
                           By default, this is the working directory of the forking process.
 * @param runJVMOptions The options to prepend to all user-specified arguments.  By default, this is empty.
 * @param connectInput If true, the standard input of the forked process is connected to the standard input of this process.  Otherwise, it is connected to an empty input stream.
                       Connecting input streams can be problematic, especially on versions before Java 7.
 * @param envVars The environment variables to provide to the forked process.  By default, none are provided.
 */
final class ForkOptions private (
    val javaHome: Option[java.io.File],
    val outputStrategy: Option[OutputStrategy],
    val bootJars: Vector[java.io.File],
    val workingDirectory: Option[java.io.File],
    val runJVMOptions: Vector[String],
    val connectInput: Boolean,
    val envVars: scala.collection.immutable.Map[String, String])
    extends Serializable {

  private def this() = this(None, None, Vector(), None, Vector(), false, Map())

  override def equals(o: Any): Boolean =
    this.eq(o.asInstanceOf[AnyRef]) || (o match {
      case x: ForkOptions =>
        (this.javaHome == x.javaHome) && (this.outputStrategy == x.outputStrategy) && (this.bootJars == x.bootJars) && (this.workingDirectory == x.workingDirectory) && (this.runJVMOptions == x.runJVMOptions) && (this.connectInput == x.connectInput) && (this.envVars == x.envVars)
      case _ => false
    })
  override def hashCode: Int = {
    37 * (37 * (37 * (37 * (37 * (37 * (37 * (37 * (17 + "sbt.ForkOptions".##) + javaHome.##) + outputStrategy.##) + bootJars.##) + workingDirectory.##) + runJVMOptions.##) + connectInput.##) + envVars.##)
  }
  override def toString: String = {
    "ForkOptions(" + javaHome + ", " + outputStrategy + ", " + bootJars + ", " + workingDirectory + ", " + runJVMOptions + ", " + connectInput + ", " + envVars + ")"
  }
  private[this] def copy(
      javaHome: Option[java.io.File] = javaHome,
      outputStrategy: Option[OutputStrategy] = outputStrategy,
      bootJars: Vector[java.io.File] = bootJars,
      workingDirectory: Option[java.io.File] = workingDirectory,
      runJVMOptions: Vector[String] = runJVMOptions,
      connectInput: Boolean = connectInput,
      envVars: scala.collection.immutable.Map[String, String] = envVars): ForkOptions = {
    new ForkOptions(javaHome, outputStrategy, bootJars, workingDirectory, runJVMOptions, connectInput, envVars)
  }
  def withJavaHome(javaHome: Option[java.io.File]): ForkOptions = {
    copy(javaHome = javaHome)
  }
  def withJavaHome(javaHome: java.io.File): ForkOptions = {
    copy(javaHome = Option(javaHome))
  }
  def withOutputStrategy(outputStrategy: Option[OutputStrategy]): ForkOptions = {
    copy(outputStrategy = outputStrategy)
  }
  def withOutputStrategy(outputStrategy: OutputStrategy): ForkOptions = {
    copy(outputStrategy = Option(outputStrategy))
  }
  def withBootJars(bootJars: Vector[java.io.File]): ForkOptions = {
    copy(bootJars = bootJars)
  }
  def withWorkingDirectory(workingDirectory: Option[java.io.File]): ForkOptions = {
    copy(workingDirectory = workingDirectory)
  }
  def withWorkingDirectory(workingDirectory: java.io.File): ForkOptions = {
    copy(workingDirectory = Option(workingDirectory))
  }
  def withRunJVMOptions(runJVMOptions: Vector[String]): ForkOptions = {
    copy(runJVMOptions = runJVMOptions)
  }
  def withConnectInput(connectInput: Boolean): ForkOptions = {
    copy(connectInput = connectInput)
  }
  def withEnvVars(envVars: scala.collection.immutable.Map[String, String]): ForkOptions = {
    copy(envVars = envVars)
  }
}
object ForkOptions {

  def apply(): ForkOptions = new ForkOptions()
  def apply(
      javaHome: Option[java.io.File],
      outputStrategy: Option[OutputStrategy],
      bootJars: Vector[java.io.File],
      workingDirectory: Option[java.io.File],
      runJVMOptions: Vector[String],
      connectInput: Boolean,
      envVars: scala.collection.immutable.Map[String, String]): ForkOptions =
    new ForkOptions(javaHome, outputStrategy, bootJars, workingDirectory, runJVMOptions, connectInput, envVars)
  def apply(
      javaHome: java.io.File,
      outputStrategy: OutputStrategy,
      bootJars: Vector[java.io.File],
      workingDirectory: java.io.File,
      runJVMOptions: Vector[String],
      connectInput: Boolean,
      envVars: scala.collection.immutable.Map[String, String]): ForkOptions =
    new ForkOptions(
      Option(javaHome),
      Option(outputStrategy),
      bootJars,
      Option(workingDirectory),
      runJVMOptions,
      connectInput,
      envVars)
}

import java.io.OutputStream

/** Configures where the standard output and error streams from a forked process go.*/
sealed abstract class OutputStrategy

object OutputStrategy {

  /**
   * Configures the forked standard output to go to standard output of this process and
   * for the forked standard error to go to the standard error of this process.
   */
  case object StdoutOutput extends OutputStrategy

  /**
   * Logs the forked standard output at the `info` level and the forked standard error at
   * the `error` level. The output is buffered until the process completes, at which point
   * the logger flushes it (to the screen, for example).
   */
  final class BufferedOutput private (val logger: Log) extends OutputStrategy with Serializable {
    override def equals(o: Any): Boolean = o match {
      case x: BufferedOutput => (this.logger == x.logger)
      case _                 => false
    }
    override def hashCode: Int = {
      37 * (17 + logger.##) + "BufferedOutput".##
    }
    override def toString: String = {
      "BufferedOutput(" + logger + ")"
    }
    private[this] def copy(logger: Log = logger): BufferedOutput = {
      new BufferedOutput(logger)
    }
    def withLogger(logger: Log): BufferedOutput = {
      copy(logger = logger)
    }
  }
  object BufferedOutput {
    def apply(logger: Log): BufferedOutput = new BufferedOutput(logger)
  }

  /**
   * Logs the forked standard output at the `info` level and the forked standard error at
   * the `error` level.
   */
  final class LoggedOutput private (val file: File) extends OutputStrategy with Serializable {
    override def equals(o: Any): Boolean = o match {
      case x: LoggedOutput => (this.file == x.file)
      case _               => false
    }
    override def hashCode: Int = {
      37 * (17 + file.##) + "LoggedOutput".##
    }
    override def toString: String = {
      "LoggedOutput(" + file + ")"
    }
    private[this] def copy(file: File = file): LoggedOutput = {
      new LoggedOutput(file)
    }
    def withFile(file: File): LoggedOutput = {
      copy(file = file)
    }
  }
  object LoggedOutput {
    def apply(file: File): LoggedOutput = new LoggedOutput(file)
  }

  /**
   * Configures the forked standard output to be sent to `output` and the forked standard error
   * to be sent to the standard error of this process.
   */
  final class CustomOutput private (val output: OutputStream) extends OutputStrategy with Serializable {
    override def equals(o: Any): Boolean = o match {
      case x: CustomOutput => (this.output == x.output)
      case _               => false
    }
    override def hashCode: Int = {
      37 * (17 + output.##) + "CustomOutput".##
    }
    override def toString: String = {
      "CustomOutput(" + output + ")"
    }
    private[this] def copy(output: OutputStream = output): CustomOutput = {
      new CustomOutput(output)
    }
    def withOutput(output: OutputStream): CustomOutput = {
      copy(output = output)
    }
  }
  object CustomOutput {
    def apply(output: OutputStream): CustomOutput = new CustomOutput(output)
  }
}

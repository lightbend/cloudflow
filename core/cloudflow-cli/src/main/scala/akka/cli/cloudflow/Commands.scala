/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow

import java.io.File
import akka.cli.cloudflow.execution.{
  ConfigurationExecution,
  ConfigureExecution,
  DeployExecution,
  ListExecution,
  ScaleExecution,
  StatusExecution,
  UndeployExecution,
  UpdateCredentialsExecution,
  VersionExecution
}
import akka.cli.cloudflow.kubeclient.KubeClient
import buildinfo.BuildInfo
import com.typesafe.config.{ Config, ConfigFactory }
import scopt.{ OParser, Read }

import scala.io.StdIn
import scala.reflect.ClassTag
import scala.util.{ Failure, Success, Try }

case class Options(
    logLevel: Option[String] = None,
    kubeConfig: Option[File] = None,
    command: Option[commands.Command[_]] = None) {
  def loggingLevel = logLevel.getOrElse("error")
}

object OptionsParser {
  import commands.format

  private val builder = OParser.builder[Options]
  import builder._

  def fromStringToFormat(str: String) = {
    str.toLowerCase() match {
      case "c" | "classic" => format.Classic
      case "t" | "table"   => format.Table
      case "json"          => format.Json
      case "yaml"          => format.Yaml
      case _               => throw new IllegalArgumentException(s"'${str}' is not a valid output format.")
    }
  }

  private val outputFmt = {
    implicit val outputfmtRead: Read[format.Format] = Read.reads(fromStringToFormat)

    import scala.language.existentials
    opt[format.Format]('o', "output")
      .text(s"available options are: 'c' or 'classic', 't' or 'table', 'json', 'yaml'")
      .action((fmt, o) => {
        val cmd = o.command match {
          case Some(v: commands.Command[_]) => Some(v.withOutput(fmt))
          case _                            => None
        }
        o.copy(command = cmd)
      })
  }

  private def detailedHelp(command: String)(msg: String) = {
    // this is hackish, scopt doesn't support short/long helps and here we are simulating the long option
    opt[Unit]("help")
      .action((_, o) => {
        val helpBuilder = OParser.builder[Unit]
        val helpParser = OParser.sequence(
          helpBuilder
            .programName(command)
            .text(msg),
          helpBuilder.help("help").text("prints this usage text"))
        OParser.parse(helpParser, Array[String]("--help"), ())
        o
      })
      .text(s"detailed '${command}' command help")
  }

  private val commonOptions = {
    OParser.sequence(
      opt[String]('v', "log-level")
        .action((l, o) => o.copy(logLevel = Some(l)))
        .text("the logging level"),
      opt[File]("kube-config")
        .action((kc, o) => o.copy(kubeConfig = Some(kc)))
        .text("the kubernetes configuration file"),
      checkConfig(
        o =>
          if (o.kubeConfig.isDefined && !o.kubeConfig.get.exists)
            failure(s"Provided Kube configuration file [${o.kubeConfig.get.getAbsolutePath}] doesn't exist")
          else success),
      help("help").text("prints this usage text"),
      note("Availble commands: " + sys.props("line.separator")))
  }

  private val versionCommand = {
    cmd("version")
      .action((_, o) => o.copy(command = Some(commands.Version())))
      .text("show the current version")
      .children(outputFmt)
  }

  private val listCommand = {
    cmd("list")
      .action((_, o) => o.copy(command = Some(commands.List())))
      .text("list available cloudflow applications")
      .children(outputFmt)
  }

  private def commandParse[C <: commands.Command[_]: ClassTag, T: Read](parser: OParser[T, Options])(f: (C, T) => C) = {
    parser
      .action((v, o) =>
        o.copy(command = o.command match {
          case Some(c: C) => Some(f(c, v))
          case _          => None
        }))
  }

  private def commandCheck[C <: commands.Command[_]: ClassTag](f: C => Either[String, Unit]) = {
    checkConfig(o =>
      o.command match {
        case Some(c: C) => f(c)
        case _          => success
      })
  }

  private val statusCommand = {
    cmd("status")
      .action((_, o) => o.copy(command = Some(commands.Status())))
      .text("show the status of a cloudflow application")
      .children(
        commandParse[commands.Status, String](arg("<cloudflowApp>"))((c, v) => c.copy(cloudflowApp = v))
          .required()
          .text("the name of the cloudflow application"),
        outputFmt)
  }

  private val getConfigurationCommand = {
    cmd("configuration")
      .action((_, o) => o.copy(command = Some(commands.Configuration())))
      .text("show the current configuration of a cloudflow application")
      .children(
        commandParse[commands.Configuration, String](arg("<cloudflowApp>"))((c, v) => c.copy(cloudflowApp = v))
          .required()
          .text("the name of the cloudflow application"),
        outputFmt)
  }

  private val deployCommand = {
    cmd("deploy")
      .action((_, o) => o.copy(command = Some(commands.Deploy())))
      .text("deploy a cloudflow applications from a cr file")
      .children(
        detailedHelp("deploy")(CommandLongDescription.deploy),
        commandParse[commands.Deploy, File](arg("<crFile>"))((c, v) => c.copy(crFile = v))
          .required()
          .text("the CR file of the cloudflow application"),
        commandParse[commands.Deploy, String](opt('u', "username"))((c, v) => c.copy(dockerUsername = v))
          .optional()
          .text("the docker registry username"),
        commandParse[commands.Deploy, String](opt('p', "password"))((c, v) => c.copy(dockerPassword = v))
          .optional()
          .text("the docker registry password"),
        commandParse[commands.Deploy, Unit](opt("password-stdin"))((c, _) =>
          c.copy(dockerPasswordStdIn = true, dockerPassword = StdIn.readLine()))
          .optional()
          .text("Take the docker registry password from std-in"),
        commandParse[commands.Deploy, Unit](opt("no-registry-credentials"))((c, _) =>
          c.copy(noRegistryCredentials = true))
          .optional()
          .text("No need for registry credentials (e.g. already in the cluster)"),
        commandParse[commands.Deploy, Map[String, String]](opt("volume-mount"))((c, v) =>
          c.copy(volumeMounts = c.volumeMounts ++ v))
          .optional()
          .unbounded()
          .text("Key/value pairs of the volume mounts"),
        commandParse[commands.Deploy, Map[String, Int]](opt("scale"))((c, v) => c.copy(scales = c.scales ++ v))
          .optional()
          .unbounded()
          .text("Key/value pairs of streamlets replicas"),
        commandParse[commands.Deploy, File](opt("conf"))((c, v) => c.copy(confs = c.confs :+ v))
          .optional()
          .unbounded()
          .text("the configuration file/s in HOCON format"),
        commandParse[commands.Deploy, Map[String, String]](arg[Map[String, String]]("config-key"))((c, v) =>
          c.copy(configKeys = c.configKeys ++ v))
          .optional()
          .unbounded()
          .text("the configuration keys for the overrides"),
        commandParse[commands.Deploy, File](opt("logback-config"))((c, f) => c.copy(logbackConfig = Some(f)))
          .optional()
          .text("the logback configuration to be applied"),
        commandParse[commands.Deploy, Seq[String]](opt("unmanaged-runtimes"))((c, r) =>
          c.copy(unmanagedRuntimes = c.unmanagedRuntimes ++ r))
          .optional()
          .text("The runtimes that should not be checked"),
        commandParse[commands.Deploy, Unit](opt("microservices"))((c, sc) => c.copy(microservices = true))
          .optional()
          .text("EXPERIMENTAL: Deploy on Akka Cloud Platform"),
        commandCheck[commands.Deploy](d => {
          if (d.logbackConfig.isDefined && !d.logbackConfig.get.exists()) {
            failure("the provided logback configuration file doesn't exist")
          } else success
        }),
        commandCheck[commands.Deploy](d => {
          if (!d.crFile.exists()) {
            failure("the provided CR file doesn't exists")
          } else success
        }),
        commandCheck[commands.Deploy](d => {
          if (!d.confs.foldLeft(true) { (acc, f) => acc && f.exists() }) {
            failure("a configuration file doesn't exists")
          } else success
        }),
        commandCheck[commands.Deploy](d => {
          if (d.noRegistryCredentials &&
              (!d.dockerUsername.isEmpty ||
              !d.dockerPassword.isEmpty)) {
            failure("--no-registry-credentials but credentials provided")
          } else if (!d.noRegistryCredentials &&
                     (d.dockerUsername.isEmpty ||
                     (d.dockerPassword.isEmpty && !d.dockerPasswordStdIn))) {
            failure(
              "Docker credentials not provided (use '-u', and '--password-stdin' or '-p'), the credentials will be stored in a Kubernetes image pull secret, so that the application can successfully be deployed")
          } else {
            success
          }
        }),
        commandCheck[commands.Deploy](d => {
          if (d.scales.values.exists(_ < 0)) {
            failure("the scale factor needs to be expressed as a number greater or equal to zero")
          } else {
            success
          }
        }),
        outputFmt)
  }

  private val updateCredentialsCommand = {
    cmd("update-docker-credentials")
      .action((_, o) => o.copy(command = Some(commands.UpdateCredentials())))
      .text("updates docker registry credentials that are used to pull Cloudflow application images.")
      .children(
        commandParse[commands.UpdateCredentials, String](arg("<cloudflowApp>"))((u, v) => u.copy(cloudflowApp = v))
          .required()
          .text("the name of the cloudflow application"),
        commandParse[commands.UpdateCredentials, String](arg("<dockerRegistry>"))((u, v) => u.copy(dockerRegistry = v))
          .required()
          .text("the name of the docker registry"),
        commandParse[commands.UpdateCredentials, String](opt('u', "username"))((u, v) => u.copy(username = v))
          .optional()
          .text("the docker registry username"),
        commandParse[commands.UpdateCredentials, String](opt('p', "password"))((u, v) => u.copy(password = v))
          .optional()
          .text("the docker registry password"),
        commandParse[commands.UpdateCredentials, Unit](opt("password-stdin"))((u, _) =>
          u.copy(dockerPasswordStdIn = true, password = StdIn.readLine()))
          .optional()
          .text("Take the docker registry password from std-in"),
        commandCheck[commands.UpdateCredentials](u => {
          if ((u.username.isEmpty ||
              u.password.isEmpty) && !u.dockerPasswordStdIn) {
            failure(
              "Docker credentials not provided (use '-u', and '--password-stdin' or '-p'), the credentials will be stored in a Kubernetes image pull secret, so that the application can successfully be deployed")
          } else {
            success
          }
        }),
        outputFmt)
  }

  private val undeployCommand = {
    cmd("undeploy")
      .action((_, o) => o.copy(command = Some(commands.Undeploy())))
      .text("undeploy a cloudflow application")
      .children(
        commandParse[commands.Undeploy, String](arg("<cloudflowApp>"))((c, v) => c.copy(cloudflowApp = v))
          .required()
          .text("the name of the cloudflow application"),
        outputFmt)
  }

  private val scaleCommand = {
    cmd("scale")
      .action((_, o) => o.copy(command = Some(commands.Scale())))
      .text("scales a streamlet of a deployed Cloudflow application to the specified number of replicas")
      .children(
        commandParse[commands.Scale, String](arg("<cloudflowApp>"))((s, v) => s.copy(cloudflowApp = v))
          .required()
          .text("the cloudflow application"),
        commandParse[commands.Scale, Map[String, Int]](arg("<streamlet>=<replicas>"))((c, v) =>
          c.copy(scales = c.scales ++ v))
          .optional()
          .unbounded()
          .text("Key/value pairs of streamlets replicas"),
        commandCheck[commands.Scale](s => {
          if (s.scales.values.exists(_ < 0)) {
            failure("the scale factor needs to be expressed as a number greater or equal to zero")
          } else {
            success
          }
        }),
        outputFmt)
  }

  private val configureCommand = {
    cmd("configure")
      .action((_, o) => o.copy(command = Some(commands.Configure())))
      .text("configures a deployed cloudflow application")
      .children(
        detailedHelp("configure")(CommandLongDescription.configure),
        commandParse[commands.Configure, String](arg("<cloudflowApp>"))((c, v) => c.copy(cloudflowApp = v))
          .required()
          .text("the cloudflow application"),
        commandParse[commands.Configure, File](opt("conf"))((c, v) => c.copy(confs = c.confs :+ v))
          .optional()
          .unbounded()
          .text("the configuration file/s in HOCON format"),
        commandParse[commands.Configure, Map[String, String]](arg[Map[String, String]]("config-key"))((c, v) =>
          c.copy(configKeys = c.configKeys ++ v))
          .optional()
          .unbounded()
          .text("the configuration keys for the overrides"),
        commandParse[commands.Configure, File](opt("logback-config"))((c, f) => c.copy(logbackConfig = Some(f)))
          .optional()
          .text("the logback configuration to be applied"),
        commandParse[commands.Configure, Unit](opt("microservices"))((c, sc) => c.copy(microservices = true))
          .optional()
          .text("EXPERIMENTAL: Deploy on Akka Cloud Platform"),
        commandCheck[commands.Configure](c => {
          if (c.logbackConfig.isDefined && !c.logbackConfig.get.exists()) {
            failure("the provided logback configuration file doesn't exist")
          } else success
        }),
        commandCheck[commands.Configure](c => {
          if (!c.confs.foldLeft(true) { (acc, f) => acc && f.exists() }) {
            failure("a configuration file doesn't exists")
          } else success
        }),
        outputFmt)
  }

  private val finalValidation = {
    checkConfig(
      o =>
        if (o.command.isEmpty) failure("Command not provided")
        else success)
  }

  val optionParser = {
    import builder._
    OParser.sequence(
      programName("kubectl-cloudflow"),
      head("kubectl-cloudflow", BuildInfo.version),
      commonOptions,
      versionCommand,
      listCommand,
      statusCommand,
      deployCommand,
      undeployCommand,
      updateCredentialsCommand,
      scaleCommand,
      configureCommand,
      getConfigurationCommand,
      finalValidation)
  }

  def apply(args: Array[String]): Option[Options] =
    OParser.parse(optionParser, args, Options())
}

object commands {
  object format {
    sealed trait Format
    final case object Classic extends Format
    final case object Table extends Format
    final case object Json extends Format
    final case object Yaml extends Format

    def Default: Format =
      sys.env.get("KUBECTL_CLOUDFLOW_OUTPUT_FORMAT").map(OptionsParser.fromStringToFormat).getOrElse(Table)
  }

  sealed trait Command[T] {
    val output: format.Format

    def execution(kubeClient: => KubeClient, logger: CliLogger): Execution[T]

    def render(result: T): String

    def withOutput(fmt: format.Format): Command[T]
  }

  case class Version(output: format.Format = format.Default) extends Command[VersionResult] {

    def execution(kubeClient: => KubeClient, logger: CliLogger): Execution[VersionResult] = {
      VersionExecution(this)
    }

    def render(vr: VersionResult) = {
      vr.render(output)
    }

    def withOutput(fmt: format.Format) = this.copy(output = fmt)
  }

  case class List(output: format.Format = format.Default) extends Command[ListResult] {

    def execution(kubeClient: => KubeClient, logger: CliLogger): Execution[ListResult] = {
      ListExecution(this, kubeClient, logger)
    }

    def render(lr: ListResult) = {
      lr.render(output)
    }

    def withOutput(fmt: format.Format) = this.copy(output = fmt)
  }

  case class Status(cloudflowApp: String = "", output: format.Format = format.Default) extends Command[StatusResult] {

    def execution(kubeClient: => KubeClient, logger: CliLogger): Execution[StatusResult] = {
      StatusExecution(this, kubeClient, logger)
    }

    def render(sr: StatusResult) = {
      sr.render(output)
    }

    def withOutput(fmt: format.Format) = this.copy(output = fmt)
  }

  case class Configuration(cloudflowApp: String = "", output: format.Format = format.Default)
      extends Command[ConfigurationResult] {

    def execution(kubeClient: => KubeClient, logger: CliLogger): Execution[ConfigurationResult] = {
      ConfigurationExecution(this, kubeClient, logger)
    }

    def render(cr: ConfigurationResult) = {
      cr.render(output)
    }

    def withOutput(fmt: format.Format) = this.copy(output = fmt)
  }

  case class Deploy(
      crFile: File = new File(""),
      dockerUsername: String = "",
      dockerPassword: String = "",
      noRegistryCredentials: Boolean = false,
      dockerPasswordStdIn: Boolean = false,
      volumeMounts: Map[String, String] = Map(),
      scales: Map[String, Int] = Map(),
      confs: Seq[File] = Seq(),
      configKeys: Map[String, String] = Map(),
      logbackConfig: Option[File] = None,
      unmanagedRuntimes: Seq[String] = Seq(),
      microservices: Boolean = false,
      output: format.Format = format.Default)
      extends Command[DeployResult]
      with WithConfiguration {

    def execution(kubeClient: => KubeClient, logger: CliLogger): Execution[DeployResult] = {
      DeployExecution(this, kubeClient, logger)
    }

    def render(dr: DeployResult) = {
      dr.render(output)
    }

    def withOutput(fmt: format.Format) = this.copy(output = fmt)

  }

  case class Undeploy(cloudflowApp: String = "", output: format.Format = format.Default)
      extends Command[UndeployResult] {

    def execution(kubeClient: => KubeClient, logger: CliLogger): Execution[UndeployResult] = {
      UndeployExecution(this, kubeClient, logger)
    }

    def render(ur: UndeployResult) = {
      ur.render(output)
    }

    def withOutput(fmt: format.Format) = this.copy(output = fmt)
  }

  case class Configure(
      cloudflowApp: String = "",
      confs: Seq[File] = Seq(),
      configKeys: Map[String, String] = Map(),
      logbackConfig: Option[File] = None,
      microservices: Boolean = false,
      output: format.Format = format.Default)
      extends Command[ConfigureResult]
      with WithConfiguration {

    def execution(kubeClient: => KubeClient, logger: CliLogger): Execution[ConfigureResult] = {
      ConfigureExecution(this, kubeClient, logger)
    }

    def render(cr: ConfigureResult) = {
      cr.render(output)
    }

    def withOutput(fmt: format.Format) = this.copy(output = fmt)
  }

  trait WithConfiguration {
    val confs: Seq[File]
    val configKeys: Map[String, String]

    def getFilesConfig() = {
      confs.foldLeft[Try[Config]](Success(ConfigFactory.empty())) { (old, f) =>
        old.flatMap { c =>
          Try {
            ConfigFactory
              .parseFile(f)
              .withFallback(c)
          }.recoverWith {
            case ex =>
              Failure[Config](CliException(s"failed to parse '${f}'", ex))
          }
        }
      }
    }

    def getKeysConfig() = {
      Try {
        val config = ConfigFactory.parseString(configKeys.map { case (k, v) => s"$k=$v" }.mkString("\n"))
        config.resolve()
      }.recoverWith {
        case ex =>
          Failure[Config](
            CliException(s"failed to parse configuration keys '${configKeys.mkString("[", ",", "]")}'", ex))
      }
    }

    lazy val aggregatedConfig = {
      for {
        fc <- getFilesConfig()
        kc <- getKeysConfig()
      } yield {
        kc.withFallback(fc)
      }
    }
  }

  case class UpdateCredentials(
      cloudflowApp: String = "",
      dockerRegistry: String = "",
      username: String = "",
      password: String = "",
      dockerPasswordStdIn: Boolean = false,
      output: format.Format = format.Default)
      extends Command[UpdateCredentialsResult] {

    def execution(kubeClient: => KubeClient, logger: CliLogger): Execution[UpdateCredentialsResult] = {
      UpdateCredentialsExecution(this, kubeClient, logger)
    }

    def render(ucr: UpdateCredentialsResult) = {
      ucr.render(output)
    }

    def withOutput(fmt: format.Format) = this.copy(output = fmt)
  }

  case class Scale(cloudflowApp: String = "", scales: Map[String, Int] = Map(), output: format.Format = format.Default)
      extends Command[ScaleResult] {

    def execution(kubeClient: => KubeClient, logger: CliLogger): Execution[ScaleResult] = {
      ScaleExecution(this, kubeClient, logger)
    }

    def render(sr: ScaleResult) = {
      sr.render(output)
    }

    def withOutput(fmt: format.Format) = this.copy(output = fmt)
  }

}

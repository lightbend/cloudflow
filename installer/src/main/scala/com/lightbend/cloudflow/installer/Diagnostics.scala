package cloudflow.installer

import java.lang.management.ManagementFactory

import akka.actor._
import akka.event.LoggingAdapter
import com.typesafe.config.Config
import com.typesafe.config.ConfigRenderOptions

import scala.collection.JavaConverters._
import scala.util._

object Diagnostics {

  def logStartOperatorMessage(settings: Settings)(implicit system: ActorSystem, log: LoggingAdapter) = {

    val blockingIODispatcherConfig = system.settings.config.getConfig("akka.actor.default-blocking-io-dispatcher")
    val dispatcherConfig           = system.settings.config.getConfig("akka.actor.default-dispatcher")
    val deploymentConfig           = system.settings.config.getConfig("akka.actor.deployment")

    log.info(s"""
      |Started Cloudflow installer ..
      |\n${box("Build Info")}
      |${formatBuildInfo}
      |\n${box("JVM Resources")}
      |${getJVMRuntimeParameters}
      |\n${box("Akka Deployment Config")}
      |\n${prettyPrintConfig(deploymentConfig)}
      |\n${box("Akka Default Blocking IO Dispatcher Config")}
      |\n${prettyPrintConfig(blockingIODispatcherConfig)}
      |\n${box("Akka Default Dispatcher Config")}
      |\n${prettyPrintConfig(dispatcherConfig)}
      |\n${box("GC Type")}
      |\n${getGCInfo}
      |\n${box("Deployment")}
      |${formatDeploymentInfo(settings)}
      |\n${box("Resource Directories")}
      |${getResourceDirectoryContent}
      |\n${box("Kubectl installed")}
      |${isKubectlIsInstalled}
      |\n${box("Oc installed")}
      |${isOcIsInstalled}
      """.stripMargin)
  }

  private def getGCInfo: List[(String, javax.management.ObjectName)] = {
    val gcMxBeans = ManagementFactory.getGarbageCollectorMXBeans()
    gcMxBeans.asScala.map(b ⇒ (b.getName, b.getObjectName)).toList
  }

  private def box(str: String): String =
    if ((str == null) || (str.isEmpty)) ""
    else {
      val line = s"""+${"-" * 80}+"""
      s"$line\n$str\n$line"
    }

  private def formatBuildInfo: String = {
    import BuildInfo._

    s"""
    |Name          : $name
    |Version       : $version
    |Scala Version : $scalaVersion
    |sbt Version   : $sbtVersion
    |Build Time    : $buildTime
    |Build User    : $buildUser
    """.stripMargin
  }

  private def formatDeploymentInfo(settings: Settings): String =
    s"""
    |Release version : ${settings.releaseVersion}
    """.stripMargin

  private def prettyPrintConfig(c: Config): String =
    c.root
      .render(
        ConfigRenderOptions
          .concise()
          .setFormatted(true)
          .setJson(false)
      )

  private def getJVMRuntimeParameters: String = {
    val runtime = Runtime.getRuntime
    import runtime._

    s"""
     |Available processors    : $availableProcessors
     |Free Memory in the JVM  : $freeMemory
     |Max Memory JVM can use  : $maxMemory
     |Total Memory in the JVM : $maxMemory
    """.stripMargin
  }

  private def getResourceDirectoryContent: String =
    os.list(ResourceDirectory.path).filter(os.isDir).map(path => path.relativeTo(ResourceDirectory.path).toString()).mkString("\n")

  private def isKubectlIsInstalled: String =
    Try(os.proc("kubectl", "version").call()) match {
      case Success(res) =>
        println(res.out.toString)
        "Yes"
      case Failure(x) =>
        x match {
          case sub: os.SubprocessException =>
            if (sub.result.exitCode < 126) "Yes" else "No"
          case _: Exception =>
            "No"
        }
    }

  private def isOcIsInstalled: String =
    Try(os.proc("oc", "version").call()) match {
      case Success(res) =>
        println(res.out.toString)
        "Yes"
      case Failure(x) =>
        x match {
          case sub: os.SubprocessException =>
            if (sub.result.exitCode < 126) "Yes" else "No"
          case _: Exception =>
            "No"
        }
    }

}

package cloudflow.installer

import akka.event.LoggingAdapter
import java.util.concurrent.TimeUnit
import org.zeroturnaround.exec._
import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

sealed trait ClusterFeature
final case object Scc                                       extends ClusterFeature
final case class StorageClasses(classes: Set[StorageClass]) extends ClusterFeature

final case class StorageClass(name: String, provisioner: String)

case class ClusterFeatures(
    storageClasses: Set[StorageClass] = Set.empty,
    hasSecurityContextConstraints: Boolean = false
) {

  private val set = {
    var s = Set.empty[ClusterFeature]
    if (storageClasses.nonEmpty) s += StorageClasses(storageClasses)
    if (hasSecurityContextConstraints) s += Scc
    s
  }

  def contains(feature: ClusterFeature) = set.contains(feature)

  def print()(implicit log: LoggingAdapter): Unit = {
    val header = s"""+${"-" * 80}+"""
    log.info(header)
    log.info("Features detected:")
    log.info("")

    if (hasSecurityContextConstraints) log.info("Scc")

    storageClasses.foreach {
      case StorageClass(name, provisioner) =>
        log.info(s"Storage class: $name - $provisioner")
    }

    log.info(header)
  }
}

object Detector {
  def apply(): Detector = Detector(executor)
  def executor(commandLine: Array[String], log: LoggingAdapter, settings: Settings): Try[String] = {

    val command = s"${commandLine.mkString(" ")}"
    log.info(s"Executing command '$command'")

    Try(
      new ProcessExecutor()
        .command(commandLine.toList.asJava)
        .readOutput(true)
        .exitValues(0)
        .timeout(settings.executionTimeout, TimeUnit.SECONDS)
        .execute()
        .outputUTF8()
    )
  }
}

case class Detector(executor: (Array[String], LoggingAdapter, Settings) => Try[String]) {
  def detectClusterFeatures()(implicit log: LoggingAdapter, settings: Settings): ClusterFeatures =
    ClusterFeatures(getStorageClasses(), hasSecurityContextConstraints())

  def hasSecurityContextConstraints()(implicit log: LoggingAdapter, settings: Settings): Boolean =
    executor("oc get scc".split(" "), log, settings).isSuccess

  def getStorageClasses()(implicit log: LoggingAdapter, settings: Settings): Set[StorageClass] = {
    @tailrec
    def extractStorageClass(a: List[String], b: Set[StorageClass] = Set.empty): Set[StorageClass] =
      a match {
        case name :: provisioner :: _ :: tail =>
          extractStorageClass(tail, b + StorageClass(name, provisioner))
        case nil @ _ => b
      }

    executor(
      "kubectl get sc --no-headers".split(" "),
      log,
      settings
    ) match {
      case Success(result) =>
        if (result.startsWith("error:")) Set.empty
        else if (result.contains("No resources found")) Set.empty
        else extractStorageClass(result.replaceAll("\n", " ").split(" ").filter(s => s != "(default)" && s != "").toList)
      case Failure(ex) =>
        log.error(s"Failed to query storage classes, ${ex.getMessage()}")
        Set.empty
    }
  }
}

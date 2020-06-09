package cloudflow.installer

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.stream._
import akka.stream.scaladsl._
import org.zeroturnaround.exec._
import os._

import scala.collection.JavaConverters._
import scala.concurrent._

sealed trait Action {
  def name: String
  def instance: CloudflowInstance.CR

  def requiredClusterFeatures: Option[ClusterFeature] = None
  def execute()(implicit
      system: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext,
      log: LoggingAdapter,
      settings: Settings
  ): Future[ActionResult]
}

abstract class KubectlAction(val name: String)(implicit cr: CloudflowInstance.CR) extends Action {

  def commandLine(): List[String]

  override def instance = cr

  override def execute()(implicit
      system: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext,
      log: LoggingAdapter,
      settings: Settings
  ): Future[ActionResult] =
    Future {

      log.info(s"Executing command '${commandLine().mkString(" ")}' for '$name'")

      try new ProcessExecutor()
        .command(commandLine().asJava)
        .readOutput(true)
        .exitValues(0)
        .timeout(settings.executionTimeout, TimeUnit.SECONDS)
        .execute()
        .outputUTF8()
      catch {
        case e: InvalidExitValueException =>
          log.error(s"Command for '$name' resulted in an error: ${e.getExitValue}")
          throw ActionFailure(this, e.getExitValue, Some(e.getResult.outputUTF8()))
      }
      log.info(s"Command for '$name' was a success")
      ActionSuccess(this)
    }
}

// Base trait for actions that use kubectl to apply configuration to the cluster
class KubectlApply(name: String)(implicit cr: CloudflowInstance.CR) extends KubectlAction(name) {

  override def instance = cr

  def overlayValues: Option[Map[String, String]] = None
  val baseOverlayValues: Map[String, String] =
    Map(
      "namespace"   -> instance.metadata.namespace,
      "instanceId"  -> instance.metadata.name,
      "instanceUid" -> instance.metadata.uid
    )

  // Directory where the transformed component will be written to, and directory that `kubectl apply` will use as
  // the base directory.
  lazy val componentDestinationDirectory = os.temp.dir()

  private def applyOverlayValues(values: Map[String, String], fileContent: String): String =
    values.foldLeft(fileContent)((content, keyValue) =>
      keyValue match {
        case (key, value) => content.replaceAll(s"__${key}__", value)
      }
    )

  private def copyComponentAndApplyOverlayValues(): Unit = {
    val basePath = ResourceDirectory.path / RelPath(name)
    os.walk(basePath)
      .filter(os.isFile)
      .foreach { kustomizeFile =>
        os.write(
          componentDestinationDirectory / kustomizeFile.relativeTo(basePath),
          applyOverlayValues(baseOverlayValues ++ overlayValues.getOrElse(Map.empty), os.read(kustomizeFile)),
          createFolders = true
        )
      }
  }
  override def execute()(implicit
      system: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext,
      log: LoggingAdapter,
      settings: Settings
  ): Future[ActionResult] = {
    copyComponentAndApplyOverlayValues()
    super.execute()
  }

  override def commandLine(): List[String] =
    List("kubectl", "apply", "-n", instance.metadata.namespace, "-k", componentDestinationDirectory.toString)
}

final case class CompositeAction(val name: String, availableClusterFeatures: ClusterFeatures, actions: List[Action])(implicit
    cr: CloudflowInstance.CR
) extends Action {

  val childActions = filterActionsBasedOnFeature(actions)
  def instance     = cr
  def execute()(implicit
      system: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext,
      log: LoggingAdapter,
      settings: Settings
  ): Future[ActionResult] =
    // oslib also drags in a Source
    akka.stream.scaladsl.Source(childActions).mapAsync(1)(_.execute()).runWith(Sink.last)

  private def filterActionsBasedOnFeature(actions: List[Action]): List[Action] =
    actions.filter(a => a.requiredClusterFeatures.fold(true)(feature => availableClusterFeatures.contains(feature)))
}

final case class PatchOwnerReferenceOfSparkMutatingWebhookConfig()(implicit cr: CloudflowInstance.CR)
    extends KubectlApply("patch-owner-reference-of-spark-mutatingwebhookconfig") {}

final case class CloudflowOperatorManagedStrimzi()(implicit cr: CloudflowInstance.CR)
    extends KubectlApply("cloudflow-operator-managed-strimzi") {
  override def overlayValues: Option[Map[String, String]] = {
    val spec = instance.spec
    Some(
      Map(
        "cloudflowOperator.persistentStorageClass"       -> spec.cloudflowOperator.persistentStorageClass,
        "kafkaClusterCr.kafkaPersistentStorageClass"     -> spec.kafkaClusterCR.kafkaPersistentStorageClass,
        "kafkaClusterCr.zookeeperPersistentStorageClass" -> spec.kafkaClusterCR.zooKeeperPersistentStorageClass,
        "cloudflowOperator.imageTag"                     -> spec.cloudflowOperator.imageTag
      )
    )
  }
}

final case class Strimzi()(implicit cr: CloudflowInstance.CR) extends KubectlApply("strimzi-operator")
final case class SparkOperator()(implicit cr: CloudflowInstance.CR) extends KubectlApply("spark-operator") {
  override def overlayValues: Option[Map[String, String]] =
    Some(
      Map(
        "sparkOperator.image" -> instance.spec.sparkOperator.image
      )
    )
}

final case class FlinkOperator()(implicit cr: CloudflowInstance.CR) extends KubectlApply("flink-operator") {
  override def overlayValues: Option[Map[String, String]] =
    Some(
      Map(
        "flinkOperator.serviceAccount" -> instance.spec.flinkOperator.serviceAccount
      )
    )
}

final case class RemoveCloudflowClusterwideResources()(implicit cr: CloudflowInstance.CR) extends KubectlAction("remove-cloudflow") {
  override def commandLine(): List[String] =
    List(
      "kubectl",
      "delete",
      "clusterrolebinding,clusterrole",
      "-l",
      s"${CloudflowLabels.InstanceId}=${instance.metadata.name}",
      "-n",
      s"${instance.metadata.namespace}"
    )
}

final case class RemoveCloudflowNamespacedResources()(implicit cr: CloudflowInstance.CR)
    extends KubectlAction("remove-cloudflow-namespaced-resources") {
  override def commandLine(): List[String] =
    List(
      "kubectl",
      "delete",
      "ns",
      s"${instance.metadata.namespace}",
      "--cascade"
    )
}

final case class AddSccToSparkServiceAccount()(implicit cr: CloudflowInstance.CR) extends KubectlAction("add-scc-to-spark-user") {
  override def requiredClusterFeatures: Option[ClusterFeature] = Some(Scc)
  override def commandLine(): List[String] =
    List(
      "oc",
      "adm",
      "policy",
      "add-scc-to-user",
      "-z",
      "cloudflow-spark",
      "anyuid",
      "-n",
      s"${instance.metadata.namespace}"
    )
}

final case class RemoveSccFromSparkServiceAccount()(implicit cr: CloudflowInstance.CR) extends KubectlAction("remove-scc-from-spark-user") {
  override def requiredClusterFeatures: Option[ClusterFeature] = Some(Scc)
  override def commandLine(): List[String] =
    List(
      "oc",
      "adm",
      "policy",
      "remove-scc-from-user",
      "-z",
      "cloudflow-spark",
      "anyuid",
      "-n",
      s"${instance.metadata.namespace}"
    )
}
final case class UpdateCRStatusAction(validationFailures: List[CloudflowInstance.ValidationFailure])(implicit cr: CloudflowInstance.CR)
    extends Action {
  def name: String = "no-operator"
  def instance     = cr
  def execute()(implicit
      system: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext,
      log: LoggingAdapter,
      settings: Settings
  ): Future[ActionResult] =
    Future {
      val status = validationFailures.map(v => v.errorMsg).mkString("\n")
      ActionFailure(this, 1, Some(status))
    }
}

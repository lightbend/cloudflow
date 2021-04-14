import akka.cli.cloudflow._
import akka.cli.cloudflow.commands
import akka.cli.cloudflow.commands.{ format, Command }
import akka.cli.cloudflow.kubeclient.KubeClientFabric8
import io.fabric8.kubernetes.client.KubernetesClient
import org.slf4j.LoggerFactory

class TestingCli(val client: KubernetesClient, logger: CliLogger = new CliLogger(None))
    extends Cli(None, (_, _) => new KubeClientFabric8(None, _ => client)(logger))(logger) {

  val testLogger = LoggerFactory.getLogger(this.getClass)

  var lastResult: String = ""

  def transform[T](cmd: Command[T], res: T): T = {
    import scala.sys.process._
    cmd match {
      case _: commands.Deploy =>
        Process(
          Seq(
            "sh",
            "-c",
            "(cd ../cloudflow-contrib/example-scripts/flink/deploy && ./deploy-application.sh swiss-knife flink-service-account)")).!
      case _: commands.Configure =>
        Process(
          Seq(
            "sh",
            "-c",
            "(cd ../cloudflow-contrib/example-scripts/flink/redeploy && ./redeploy-application.sh swiss-knife flink-service-account)")).!
      case _: commands.Undeploy =>
        Process(
          Seq(
            "sh",
            "-c",
            "(cd ../cloudflow-contrib/example-scripts/flink/undeploy && ./undeploy-application.sh swiss-knife)")).!
      case _ =>
    }

    val newResult = cmd.toString + "\n" + res.asInstanceOf[Result].render(format.Table)
    if (newResult != lastResult) {
      lastResult = newResult
      testLogger.debug(newResult)
    }
    res
  }

  def handleError[T](cmd: Command[T], ex: Throwable): Unit = {
    testLogger.warn(s"Error executing command ${cmd}", ex)
  }
}

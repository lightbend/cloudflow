package cloudflow.installer

import akka.actor._
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.stream._
import skuber._
import skuber.api.Configuration
import skuber.apiextensions._

import scala.concurrent._
import scala.concurrent.duration._

object Main {
  def main(args: Array[String]): Unit = {

    if (!ResourceDirectory.exists()) {
      println("The Cloudflow installer could not locate the resource directory.")
      System.exit(1)
    }

    implicit val system              = ActorSystem()
    implicit val log: LoggingAdapter = Logging(system, "Cloudflow Installer")

    try {
      implicit val mat      = createMaterializer()
      implicit val ec       = system.dispatcher
      implicit val settings = Settings(system)

      Diagnostics.logStartOperatorMessage(settings)

      val client = connectToKubernetes()

      installCRD(client)

      HealthChecks.serve(settings)
      Operator.handleEvents(client)

    } catch {
      case t: Throwable ⇒
        log.error(t, "Unexpected error starting Cloudflow install operator, terminating.")
        system.registerOnTermination(exitWithFailure)
        system.terminate()
    }

  }

  private def createMaterializer()(implicit system: ActorSystem) = {
    val decider: Supervision.Decider = (_ ⇒ Supervision.Stop)
    ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))
  }

  private def exitWithFailure(): Unit = System.exit(-1)

  private def connectToKubernetes()(implicit system: ActorSystem, mat: Materializer, log: LoggingAdapter) = {
    val conf   = Configuration.defaultK8sConfig
    val client = k8sInit(conf).usingNamespace("")
    log.info(s"Connected to Kubernetes cluster: ${conf.currentContext.cluster.server}")
    client
  }

  private def installCRD(client: skuber.api.client.KubernetesClient)(implicit ec: ExecutionContext): Unit = {
    val crdTimeout = 20.seconds
    // TODO check if version is the same, if not, also create.
    import CloudflowInstance._
    Await.ready(
      client.getOption[CustomResourceDefinition](CRD.name).map { result ⇒
        result.fold(client.create(CRD)) { crd ⇒
          if (crd.spec.version != CRD.spec.version) {
            client.create(CRD)
          } else {
            Future.successful(crd)
          }
        }
      },
      crdTimeout
    )
  }

}

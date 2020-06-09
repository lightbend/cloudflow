package cloudflow.installer

import java.nio.file.NoSuchFileException

import akka.actor.ActorSystem
import akka.stream._

import scala.concurrent._

import akka.event.LoggingAdapter

trait ActionExecutor {
  def execute(
      action: Action
  )(implicit
    system: ActorSystem,
    materializer: Materializer,
    ec: ExecutionContext,
    log: LoggingAdapter,
    settings: Settings): Future[ActionResult]
}

case object KubectlActionExecutor extends ActionExecutor {
  override def execute(
      action: Action
  )(implicit
    system: ActorSystem,
    materializer: Materializer,
    ec: ExecutionContext,
    log: LoggingAdapter,
    settings: Settings): Future[ActionResult] =
    action.execute().recover {
      case actionFailure: ActionFailure =>
        actionFailure
      case exception: NoSuchFileException =>
        ActionFailure(action, 1, Some(s"Cannot find file '${exception.getMessage}"))
      case exception: Exception =>
        ActionFailure(action, 1, Some(exception.getMessage))
    }
}

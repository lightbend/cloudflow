package cloudflow.installer

// This is the result of an action, the result is the basis for logging, eventing and status updates
sealed trait ActionResult {
  def action: Action
  def exitCode: Int
  def stdOut: Option[String]
  def stdErr: Option[String]

  def toStatus: CloudflowInstance.Status
}
case class ActionFailure(action: Action, exitCode: Int, stdErr: Option[String] = None)
    extends Exception(stdErr.getOrElse(""))
    with ActionResult {
  override def stdOut: Option[String] = None
  override def toStatus: CloudflowInstance.Status =
    CloudflowInstance.Status("Failed", stdErr.map(_.take(maxStatusSize)))
  private val maxStatusSize = 1024
}
case class ActionSuccess(action: Action) extends ActionResult {
  override def exitCode: Int          = 0
  override def stdOut: Option[String] = None
  override def stdErr: Option[String] = None
  override def toStatus: CloudflowInstance.Status =
    CloudflowInstance.Status("Installed")
}

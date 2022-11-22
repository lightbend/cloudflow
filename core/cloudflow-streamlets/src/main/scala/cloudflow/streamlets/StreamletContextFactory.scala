package cloudflow.streamlets

trait StreamletContextFactory[Context <: StreamletContext] {

  def newContext(definition: StreamletDefinition): Context

}

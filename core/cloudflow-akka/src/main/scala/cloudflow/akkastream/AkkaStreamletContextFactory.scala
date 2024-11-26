package cloudflow.akkastream

import akka.actor.ActorSystem
import cloudflow.streamlets._

class AkkaStreamletContextFactory(system: ActorSystem) extends StreamletContextFactory[AkkaStreamletContext] {

  def newContext(definition: StreamletDefinition): AkkaStreamletContext = {
    new AkkaStreamletContextImpl(definition, system)
  }
}

package helloworld

import akka.stream.scaladsl._
import cloudflow.akkastream._
import cloudflow.akkastream.scaladsl._
import cloudflow.streamlets._
import scala.util.Try
import scala.concurrent.duration._

class HelloWorldShape extends AkkaStreamlet {
  val shape = StreamletShape.empty

  def createLogic = new RunnableGraphStreamletLogic() {
    def runnableGraph = {
      Source
        .cycle(() => Iterator.continually("hello world!"))
        .throttle(1, 1.second)
        .map(println)
        .to(Sink.ignore)
    }
  }
}

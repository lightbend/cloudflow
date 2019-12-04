package cloudflowx.egress

import java.io.PrintStream

import cloudflow.akkastream._
import cloudflow.streamlets._

/**
 * Abstraction for writing to output to the console (i.e., stdout).
 * @param in CodecInlet for records of type IN
 * @param prefix prepended to each record. _Add punctuation and whitespace if you want it_
 * @param output PrintStream, which defaults to [[Console.out]].
 */
final case class ConsoleEgressLogic[IN](
    in:     CodecInlet[IN],
    prefix: String,
    out:    PrintStream    = Console.out)(
    implicit
    context: AkkaStreamletContext) extends FlowEgressLogic[IN](in) {

  def write(record: IN): Unit =
    out.println(prefix + record.toString)
}

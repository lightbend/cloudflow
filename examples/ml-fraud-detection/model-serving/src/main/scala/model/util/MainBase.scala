package model.util

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }

import scala.concurrent.duration._
import modelserving.model.ModelDescriptor
import model.ModelDescriptorUtil.implicits._

/**
 * Implements the boilerplate required for all the test "main" programs in the
 * pipelines example apps. _Not_ intended for use when deploying actual pipelines
 * apps. To use this class for a project called `foo.bar.mypipeline` and
 * a test program called `MyPipelineMain`, to write a record ingress test:
 * ```
 * object MyPipelineRecordIngressTestMain
 *     extends MainBase[MyRecord](myDefCount, myDefFreq) {
 *   protected def makeSource(): Source[MyRecord, NotUsed] = {
 *     // logic to create an Akka Streams Source
 *   }
 * }
 * ```
 * For model tests, see {@link ModelMainBase}.
 *
 * Note: At this time, Pipelines intercepts calls to sbt run and sbt runMain,
 * so use the console instead, e.g., for a project called `foo.bar.mypipeline`
 * and a test program called `MyPipelineMain`:
 * ```
 * import foo.bar.mypipeline._
 * MyPipelineMain.main(Array("-n", "5","-f","1000"))
 * ```
 *
 * @param defaultCount the default value for the number of records or models to process.
 * @param defaultFrequencyMillis the default value for _milliseconds_ between each one.
 *        Note that model classes may define their default values in seconds, so convert!
 */
abstract class MainBase[T](
    val defaultCount:           Int            = 10,
    val defaultFrequencyMillis: FiniteDuration = 1000.milliseconds) {

  /**
   * Make a Source of instances. Implement this for your record type "T" for record
   * tests, use [[ModelDescriptor]] for "T" for model tests.
   * @param frequency if your Source already provides throttling. Otherwise, ignore.
   */
  protected def makeSource(frequency: FiniteDuration): Source[T, NotUsed]

  /**
   * Normally, this default implementation or the implementation in
   * the ModelMainBase will be sufficient, but override it when needed.
   */
  protected def toString(t: T): String = t.toString

  // Akka restricts names for actors to [a-zA-Z0-9_-], where '_' and '-' can't
  // be the first character.
  val className = this.getClass.getName.replaceAll("[^a-zA-Z0-9_-]", "_")

  def main(args: Array[String]): Unit = {
    val (count, frequency) =
      MainBase.parseArgs(args, className, defaultCount, defaultFrequencyMillis)
    implicit val system = ActorSystem(className)
    implicit val mat = ActorMaterializer()
    var i = 1
    makeSource(frequency).runWith {
      Sink.foreach { t ⇒
        println(toString(t))
        if (i == count) {
          println("Finished!")
          sys.exit(0)
        }
        i += 1
      }
    }
    println("Should never get here...")
  }
}

/**
 * Methods extracted from the companion abstract class so they can be used by
 * test classes that can't use the rest of MainBase.
 */

object MainBase {
  /**
   * Help, when the help option is specified or an invalid input is entered.
   */
  def help(className: String, defaultCount: Int, defaultFrequencyMillis: FiniteDuration) =
    println(s"""
      |usage: $className [-h|--help] [-n|--count N] [-f|--frequency F]
      |where:
      |  -h | --help         print this message and exit
      |  -n | --count N      print N records and stop (default: $defaultCount)
      |  -f | --frequency F  milliseconds between output model descriptions (default: $defaultFrequencyMillis)
      |""".stripMargin)

  /**
   * Parse the input arguments.
   * @param args the command-line arguments to parse.
   * @param className the name of the main class (for help messages).
   * @param defaultCount the default number of models/records to process before quitting.
   * @param defaultFrequencyMillis  the milliseconds between models/records.
   */
  def parseArgs(
      args:                   Seq[String],
      className:              String,
      defaultCount:           Int,
      defaultFrequencyMillis: FiniteDuration): (Int, FiniteDuration) = {
      def pa(args2: Seq[String], nf: (Int, FiniteDuration)): (Int, FiniteDuration) = args2 match {
        case ("-h" | "--help") +: _ ⇒
          help(className, defaultCount, defaultFrequencyMillis)
          sys.exit(0)
        case Nil                                 ⇒ nf
        case ("-n" | "--count") +: n +: tail     ⇒ pa(tail, (n.toInt, nf._2))
        case ("-f" | "--frequency") +: n +: tail ⇒ pa(tail, (nf._1, n.toInt.milliseconds))
        case x +: _ ⇒
          println(s"ERROR: Unrecognized argument $x. All args: ${args.mkString(" ")}")
          help(className, defaultCount, defaultFrequencyMillis)
          sys.exit(1)
      }
    val (count, frequency) = pa(args, (defaultCount, defaultFrequencyMillis))
    println(className + ":")
    println(s"printing $count records")
    println(s"frequency: $frequency milliseconds")
    (count, frequency)
  }
}

/**
 * Subclass of [[MainBase]] specifically for Model ingress and related tests,
 * because the "T" type parameter for MainBase is known to be [[ModelDescriptor]].
 * To use this class for a project called `foo.bar.mypipeline` and
 * a test program called `MyPipelineMain`, to write a model ingress test:
 * ```
 * object MyPipelineModelIngressTestMain
 *     extends ModelMainBase(myDefCount, myDefFreq) {
 *   protected def makeSource(): Source[ModelDescriptor, NotUsed] = {
 *     // logic to create an Akka Streams Source
 *   }
 * }
 * ```
 * @param defaultCount the default value for the number of records or models to process.
 * @param defaultFrequencyMillis the default value for _milliseconds_ between each one.
 *        Note that model classes may define their default values in seconds, so convert!
 */
abstract class ModelMainBase(
    defaultCount:           Int            = 10,
    defaultFrequencyMillis: FiniteDuration = 1000.milliseconds)
  extends MainBase[ModelDescriptor](defaultCount, defaultFrequencyMillis) {

  override protected def toString(m: ModelDescriptor): String = m.toString
}

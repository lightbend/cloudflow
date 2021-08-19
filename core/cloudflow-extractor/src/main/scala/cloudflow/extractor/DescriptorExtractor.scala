/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.extractor

import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }

import java.io.File
import java.net.{ URL, URLClassLoader }
import scala.util.{ Failure, Success, Try }

case class ExtractResult(descriptors: Map[String, Config] = Map(), problems: Vector[ExtractProblem] = Vector()) {
  def problemsMessage = problems.map(_.message).mkString("\n")
  def toTry: Try[Map[String, Config]] =
    if (problems.isEmpty) Success(descriptors) else Failure(new Exception(problemsMessage))
}

sealed trait ExtractProblem {
  def streamletClass: Class[_]
  def className = streamletClass.getName
  def message: String
  def errorMsg(error: Throwable): String = {
    val cause = error match {
      case e: java.lang.reflect.InvocationTargetException =>
        Option(e.getCause).getOrElse(e)
      case _ => error
    }

    s"""an ${cause.getClass.getSimpleName}${Option(cause.getMessage).map(": " + _).getOrElse("")}"""
  }
}

final case class ConstructorMissing(streamletClass: Class[_]) extends ExtractProblem {
  def message =
    s"Streamlet '${streamletClass.getName}' could not be instantiated for introspection. It has no default constructor."
}

final case class ConstructorFailure(streamletClass: Class[_], error: Throwable) extends ExtractProblem {
  def message =
    s"Streamlet '${streamletClass.getName}' could not be instantiated for introspection. Its constructor threw ${errorMsg(error)}"
}

final case class DescriptorMethodMissing(streamletClass: Class[_]) extends ExtractProblem {
  def message = s"Streamlet '${streamletClass.getName}' is not usable. It has no descriptor method to call."
}

final case class DescriptorMethodFailure(streamletClass: Class[_], error: Throwable) extends ExtractProblem {
  def message =
    s"Streamlet '${streamletClass.getName}' could not be introspected. Its descriptor method threw ${errorMsg(error)}"
}

object DescriptorExtractor {

  final case class ScanConfiguration(projectId: String, classpathUrls: Array[URL])
  final case class ResolveConfiguration(dockerImageName: String)

  def scan(config: ScanConfiguration): ExtractResult = {
    val cl = new URLClassLoader(config.classpathUrls, ClassLoader.getSystemClassLoader.getParent)
    val (problemsMap, descriptorsMap) = StreamletScanner
      .scanForStreamletDescriptors(cl, config.projectId)
      .partition { case (_, descriptorResult) => descriptorResult.isLeft }
    ExtractResult(descriptors = descriptorsMap.collect {
      case (className, Right(descriptor)) => className -> descriptor
    }, problems = problemsMap.collect { case (className, Left(problem)) => problem }.toVector)
  }

  def resolve(config: ResolveConfiguration, extractResult: ExtractResult): Config = {
    val descriptor = extractResult.descriptors.foldLeft(ConfigFactory.empty) {
      case (acc, (name, conf)) =>
        acc.withValue(
          s""""$name"""",
          conf
            .root()
            .withValue("image", ConfigValueFactory.fromAnyRef(config.dockerImageName)))
    }

    descriptor
  }
}

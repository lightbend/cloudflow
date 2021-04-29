/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow.execution

import akka.cli.cloudflow.CliException

import scala.util.matching.Regex
import scala.util.matching.Regex.Match
import scala.util.{ Failure, Success, Try }

final case class Image(
    registry: Option[String] = None,
    repository: Option[String] = None,
    image: String,
    tag: Option[String] = None)

object Image {

  /*
    See https://docs.dockerclient.com/engine/reference/commandline/tag/
    A tag name must be valid ASCII and may contain lowercase and uppercase letters, digits, underscores, periods and dashes.
    A tag name may not start with a perßiod or a dash and may contain a maximum of 128 characters.
    A tag contain lowercase and uppercase letters, digits, underscores, periods and dashes
    (It can also contain a : which the docs don't mention, for instance sha256:<hash>)
   */
  private val imageRefRegex = {
    new Regex(
      "^((([a-zA-Z0-9-.:]{0,253}))/)?((?:[a-z0-9-_./]+/)?)([a-z0-9-_.]+)(?:[:@]([^.-][a-zA-Z0-9-_.:]{0,127})?)?$",
      "registry_with_slash",
      "registry",
      "registry_second_capture",
      "repository",
      "image",
      "tag")
  }

  def apply(ref: String): Try[Image] = {

    val imageRef = ref.trim

    val captured = imageRefRegex.findFirstMatchIn(imageRef)

    def extract(matched: Match)(gn: String) = {
      if (matched.groupNames.exists(_ == gn) && matched.group(gn) != null && !matched.group(gn).trim.isEmpty) {
        Option(matched.group(gn))
      } else {
        None
      }
    }

    val failureMsg = s"Failed to parse the docker image reference '${imageRef}'"

    (imageRef, captured) match {
      case (s, _) if s.startsWith(":") || s.endsWith(":") || s.startsWith("http://") || s.startsWith("https://") =>
        Failure(CliException(
          s"The following docker image path is not valid: '${ref}' A common error is to prefix the image path with a URI scheme like 'http' or 'https'."))
      case (_, Some(v)) =>
        val ex = extract(v)(_)
        ex("image") match {
          case Some(image) if !image.trim.isEmpty && !image.startsWith(":") && !image.endsWith(":") =>
            // this is a shortcoming in using a regex for this, since it will always eagerly match the first part as the registry.
            val (registry, repository) = {
              (ex("registry"), ex("repository").map(_.stripSuffix("/"))) match {
                case (Some(registry), None) =>
                  (None, Some(registry))
                case rr => rr
              }
            }

            ex("tag") match {
              case None =>
                Success(Image(registry, repository, image, None))
              case t @ Some(tag)
                  if !tag.startsWith(".") && !tag.startsWith("-") && !tag.startsWith(":") && !tag.endsWith(":") && !(tag
                    .contains(':') && tag
                    .split(':')
                    .size > 2) =>
                Success(Image(registry, repository, image, t))
              case _ =>
                Failure(CliException(s"${failureMsg}, reason: invalid tag"))
            }
          case _ =>
            Failure(CliException(s"${failureMsg}, reason: image name not found or invalid"))
        }
      case _ => Failure(CliException(failureMsg))
    }
  }

}

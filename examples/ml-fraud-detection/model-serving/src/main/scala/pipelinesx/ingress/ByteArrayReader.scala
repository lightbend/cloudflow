package pipelinesx.ingress

import pipelinesx.logging.{ Logger, LoggingUtil }
import java.io.{ BufferedInputStream, FileInputStream, InputStream }

/**
 * Return a byte array read from a file source, either in a file system or the
 * CLASSPATH.
 * TODO: Add fromURL (see [[RecordsReader]]).
 * TODO: Combine with [[RecordsReader]] to enable the latter to better support binary?
 */
object ByteArrayReader {

  val logger: Logger = LoggingUtil.getLogger(ByteArrayReader.getClass)

  def fromFileSystem(path: String): Either[String, Array[Byte]] = try {
    readBytes(path, "file system", new FileInputStream(path))
  } catch {
    case scala.util.control.NonFatal(th) ⇒ Left(th.getMessage)
  }

  // HACK: If the resource doesn't exist and doesn't start with '/', try adding it!
  // If it doesn't exist but _has_ the leading '/', try removing it!
  def fromClasspath(path: String): Either[String, Array[Byte]] = {
    val errFmt = "CLASSPATH resource paths %s and %s do not exist!"
    val slashFmt = "It was necessary to %s leading '/' %s the beginning of the CLASSPATH path %s"

      def fixPath(): Either[String, String] = {
        val clazz = getClass
        if (clazz.getResource(path) != null) Right(path)
        else if (path.startsWith("/")) {
          val path2 = path.substring(1)
          if (clazz.getResource(path2) != null) {
            logger.warn(slashFmt.format("remove the", "from", path))
            Right(path2)
          } else Left(errFmt.format(path, path2))
        } else if (clazz.getResource("/" + path) != null) {
          logger.warn(slashFmt.format("add a", "to", path))
          Right("/" + path)
        } else Left(errFmt.format(path, "/" + path))
      }

    fixPath() match {
      case Left(error) ⇒ Left(error)
      case Right(p) ⇒
        val is = new BufferedInputStream(getClass.getResourceAsStream(p))
        readBytes(p, "CLASSPATH", is)
    }
  }

  val MaxBytes: Int = 1024 * 1024 * 1024

  private def maxSizeExceeded(path: String, origin: String) =
    s"We reached the maximum allowed size of $MaxBytes while reading the $origin resource from path $path"
  private def loadFailed(path: String, origin: String, cause: Throwable) =
    s"We failed to successfully load the $origin resource from path $path. Cause: $cause"

  protected def readBytes(
      path:   String,
      origin: String,
      is:     InputStream): Either[String, Array[Byte]] = try {
      def chunk(): (Boolean, Array[Byte]) = {
        val len = MaxBytes / 1024 // arbitrary size
        val buffer = Array.fill[Byte](len)(0)
        val count = is.read(buffer)
        count match {
          case -1 | 0 ⇒ (true, Array.empty)
          case `len`  ⇒ (false, buffer) // false because there might be more to read
          case _      ⇒ (true, buffer.take(count)) // truncate
        }
      }

      @annotation.tailrec
      def recurse(buffer: Array[Byte]): Either[String, Array[Byte]] =
        if (buffer.length >= MaxBytes) {
          Left(maxSizeExceeded(path, origin))
        } else {
          val (finished, b) = chunk()
          if (finished) Right(buffer ++ b)
          else recurse(buffer ++ b)
        }

    val result = recurse(Array.empty)
    result
  } catch {
    case scala.util.control.NonFatal(th) ⇒
      Left(loadFailed(path, origin, th))
  } finally {
    is.close()
  }
}


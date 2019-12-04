package pipelinesx.ingress

import org.scalatest.FunSpec

class ByteArrayReaderTest extends FunSpec {

  describe("ByteArrayReader") {
    describe("fromFileSystem()") {
      it("returns a Left(error) if the resource isn't found") {
        ByteArrayReader.fromFileSystem("foo") match {
          case Left(error@_)  ⇒ // pass
          case Right(bytes@_) ⇒ fail("Returned bytes for a non-existent file!")
        }
      }

      it("returns a byte array for the contents of a file from the file system") {
        ByteArrayReader.fromFileSystem("build.sbt") match {
          case Left(error)    ⇒ fail(s"Failed to return bytes for the file build.sbt: error = $error")
          case Right(bytes@_) ⇒ // pass
        }
      }

      it("the returned byte array is the contents of the file") {
        ByteArrayReader.fromFileSystem("build.sbt") match {
          case Left(error) ⇒ fail(s"Failed to return bytes for the file build.sbt: error = $error")
          case Right(bytes) ⇒
            val expected = scala.io.Source.fromFile("build.sbt").getLines.reduceLeft(_ + "\n" + _)
            (bytes zip expected.getBytes).zipWithIndex foreach {
              case ((a, b), i) ⇒
                assert(a == b, s"$i, $a != $b")
            }
        }
      }
    }

    describe("fromClasspath()") {
      it("returns a Left(error) if the resource isn't found") {
        ByteArrayReader.fromClasspath("foo") match {
          case Left(error@_)  ⇒ // pass
          case Right(bytes@_) ⇒ fail("Returned bytes for a non-existent file!")
        }
      }

      val dataFile = "good-records1.csv"

      it("returns a byte array for the contents of a file from the CLASSPATH") {
        ByteArrayReader.fromClasspath("/" + dataFile) match {
          case Left(error)    ⇒ fail(s"Failed to return bytes for the file $dataFile: error = $error")
          case Right(bytes@_) ⇒ // pass
        }
      }

      it("the returned byte array is the contents of the file") {
        ByteArrayReader.fromClasspath("/" + dataFile) match {
          case Left(error) ⇒ fail(s"Failed to return bytes for the file $dataFile: error = $error")
          case Right(bytes) ⇒
            val expected = scala.io.Source.fromResource(dataFile).getLines.reduceLeft(_ + "\n" + _)
            (bytes zip expected.getBytes).zipWithIndex foreach {
              case ((a, b), i) ⇒
                assert(a == b, s"$i, $a != $b")
            }
        }
      }
    }
  }
}

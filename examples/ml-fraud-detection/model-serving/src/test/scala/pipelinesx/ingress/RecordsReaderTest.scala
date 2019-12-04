package pipelinesx.ingress

import org.scalatest.{ FunSpec, BeforeAndAfterAll }
import pipelinesx.test.OutputInterceptor
import pipelinesx.logging.StdoutStderrLogger
import java.io.File
import java.net.URL

class RecordsReaderTest extends FunSpec with BeforeAndAfterAll with OutputInterceptor {

  override def afterAll: Unit = {
    resetOutputs()
  }

  val clazz = this.getClass()
  val className = clazz.getName()
  RecordsReader.logger.setLogger(StdoutStderrLogger(clazz))

  val testGoodRecordsResources = Array("good-records1.csv", "good-records2.csv")
  val testBadRecordsResources = Array("bad-records.csv")
  val testGoodRecordsFiles = testGoodRecordsResources.map(s => new File("pipelinesx/src/test/resources/" + s))
  val testBadRecordsFiles = testBadRecordsResources.map(s => new File("pipelinesx/src/test/resources/" + s))
  val testGoodRecordsURLs = Array(new URL("https://lightbend.com/about-lightbend"))
  val testBadRecordsURLs = Array(new URL("http://example.foo"))
  def identityR = (r: String) => Right(r)

  def intStringTupleCSVParse(separator: String = ","): String => Either[String,(Int,String)] =
    (r: String) => r.split(separator) match {
      case Array(i, s) =>
        try { Right(i.toInt -> s) }
        catch { case scala.util.control.NonFatal(e) => Left(e.toString) }
      case _ => Left(r)
    }
  def nonEmptyIntArrayCSVParse(separator: String = ","): String => Either[String,Array[Int]] =
    (r: String) => {
      val array = r.split(separator)
      if (array.length == 0) Left("empty array!")
      else try {
        Right(array.map(_.toInt))
      } catch {
        case scala.util.control.NonFatal(e) => Left(s"Non-integer elements: $r. (${e})")
      }
    }


  describe("RecordsReader") {
    describe("fromFileSystem()") {
      it("throws an exception if no resources are specified") {
        intercept[RecordsReader$NoResourcesSpecified$] {
          // A different exception is thrown earlier if failIfMissing takes its default value of true
          ignoreOutput {
            RecordsReader.fromFileSystem(Nil, failIfMissing = false)(identityR)
            ()
          }
        }
      }

      it("Loads one or more file resources from the file system") {
        ignoreOutput {
          assert(RecordsReader.fromFileSystem(testGoodRecordsFiles)(identityR).next() != null)
          ()
        }
      }

      it("Raises an exception if the file doesn't exist") {
        intercept[RecordsReader.FailedToLoadResources[_]] {
          ignoreOutput {
            RecordsReader.fromFileSystem(Seq(new File("foobar")))(identityR)
            ()
          }
        }
      }
    }

    describe("fromClasspath()") {
      it("throws an exception if no resources are specified") {
        intercept[RecordsReader$NoResourcesSpecified$] {
          ignoreOutput {
            // A different exception is thrown earlier if failIfMissing takes its default value of true
            RecordsReader.fromClasspath(Nil, failIfMissing = false)(identityR)
            ()
          }
        }
      }

      it("Loads one or more file resources from the classpath") {
        ignoreOutput {
          assert(RecordsReader.fromClasspath(testGoodRecordsResources)(identityR).next() != null)
          ()
        }
      }

      it("Raises an exception if the resource doesn't exist") {
        intercept[RecordsReader.FailedToLoadResources[_]] {
          ignoreOutput {
            RecordsReader.fromClasspath(Seq("foobar"))(identityR)
            ()
          }
        }
      }
    }

    describe("fromURLs()") {
      it("throws an exception if no resources are specified") {
        intercept[RecordsReader$NoResourcesSpecified$] {
          ignoreOutput {
            // A different exception is thrown earlier if failIfMissing takes its default value of true
            RecordsReader.fromURLs(Nil, failIfMissing = false)(identityR)
            ()
          }
        }
      }

      it("Loads one or more file resources from the URL") {
        ignoreOutput {
          assert(
            RecordsReader.fromURLs(testGoodRecordsURLs)(identityR).next() != null
          )
          ()
        }
      }

      it("Raises an exception if the resource doesn't exist") {
        intercept[RecordsReader$FailedToLoadResources] {
          ignoreOutput {
            RecordsReader.fromURLs(testBadRecordsURLs)(identityR)
            ()
          }
        }
      }
    }

    describe("fromConfiguration()") {
      it("throws an exception if the specified configuration root key is not found") {
        intercept[RecordsReader$InvalidConfiguration] {
          ignoreOutput {
            RecordsReader.fromConfiguration("bad")(identityR)
            ()
          }
        }
      }

      it("throws an exception if 'data-sources' under the specified configuration root key is not found") {
        intercept[RecordsReader$InvalidConfiguration] {
          ignoreOutput {
            RecordsReader.fromConfiguration("records-reader-test-without-data-sources")(identityR)
            ()
          }
        }
      }

      it("throws an exception if 'which-source' is not found under 'data-sources'") {
        intercept[RecordsReader$InvalidConfiguration] {
          ignoreOutput {
            RecordsReader.fromConfiguration("records-reader-test-without-which-source")(identityR)
            ()
          }
        }
      }

      it("throws an exception if 'which-source' is not 'CLASSPATH', 'FileSystem', or 'URLs'") {
        intercept[RecordsReader$InvalidConfiguration] {
          ignoreOutput {
            RecordsReader.fromConfiguration("records-reader-test-empty-which-source")(identityR)
            ()
          }
        }
        intercept[RecordsReader$InvalidConfiguration] {
          ignoreOutput {
            RecordsReader.fromConfiguration("records-reader-test-invalid-which-source")(identityR)
            ()
          }
        }
      }

      Seq("classpath", "filesystem", "urls"). foreach { x =>
        it(s"throws an exception when $x source is specified, but the configuration for it is missing") {
          intercept[RecordsReader$InvalidConfiguration] {
            ignoreOutput {
              RecordsReader.fromConfiguration(s"records-reader-test-$x-without-$x")(identityR)
              ()
            }
          }
        }

        it(s"throws an exception when $x source is specified, but the configuration for it is empty") {
          intercept[RecordsReader$InvalidConfiguration] {
            ignoreOutput {
              RecordsReader.fromConfiguration(s"records-reader-test-$x-with-empty-$x")(identityR)
              ()
            }
          }
        }
      }

      it("throws an exception if the CLASSPATH 'paths' is empty") {
        intercept[RecordsReader$InvalidConfiguration] {
          ignoreOutput {
            RecordsReader.fromConfiguration("records-reader-test-classpath-with-empty-paths")(identityR)
            ()
          }
        }
      }

      it("throws an exception if the CLASSPATH 'paths' entries are invalid") {
        intercept[RecordsReader$ConfigurationError] {
          ignoreOutput {
            RecordsReader.fromConfiguration("records-reader-test-classpath-with-invalid-paths")(identityR)
            ()
          }
        }
      }

      it("throws an exception if the File System 'paths' and 'dir-paths' are both empty") {
        intercept[RecordsReader$InvalidConfiguration] {
          ignoreOutput {
            RecordsReader.fromConfiguration("records-reader-test-filesystem-with-empty-paths-and-dir-paths")(identityR)
            ()
          }
        }
      }

      val colonDelimitedLine = (1,"7.4;0.7;0;1.9;0.076;11;34;0.9978;3.51;0.56;9.4;5")

      it("does not throw an exception if the File System 'dir-paths' entries is non-empty but 'file-name-regex' not found ('' is used)") {
        ignoreOutput {
          assert(colonDelimitedLine ==
            RecordsReader.fromConfiguration("records-reader-test-filesystem-with-nonempty-dir-paths-empty-file-name-regex")(identityR).next())
          ()
        }
      }

      it("finds the files matching the File System 'dir-paths' 'file-name-regex' entries") {
        ignoreOutput {
          assert(colonDelimitedLine ==
            RecordsReader.fromConfiguration("records-reader-test-filesystem-with-nonempty-dir-paths-nonempty-matching-file-name-regex")(identityR).next())
          ()
        }
      }

      it("throws an exception if the File System 'dir-paths' and 'file-name-regex' don't match any files") {
        intercept[RecordsReader$FailedToLoadResources] {
          ignoreOutput {
            RecordsReader.fromConfiguration("records-reader-test-filesystem-with-nonempty-dir-paths-nonempty-nonmatching-file-name-regex")(identityR).next()
            ()
          }
        }
      }

      it("finds the File System 'paths' files instead of the 'dir-paths', if both are nonempty") {
        ignoreOutput {
          assert((1,"1,") ==
            RecordsReader.fromConfiguration("records-reader-test-filesystem-with-nonempty-paths-and-dir-paths")(identityR).next())
          ()
        }
      }

      it("throws an exception if either of the URLs 'base-urls' or 'files' are empty") {
        intercept[RecordsReader$ConfigurationError] {
          ignoreOutput {
            RecordsReader.fromConfiguration("records-reader-test-urls-with-empty-base-urls")(identityR).next()
            ()
          }
        }
        intercept[RecordsReader$ConfigurationError] {
          ignoreOutput {
            RecordsReader.fromConfiguration("records-reader-test-urls-with-empty-files")(identityR).next()
            ()
          }
        }
      }

      it("loads one or more file resources from the CLASSPATH when the configuration specifies that source") {
        ignoreOutput {
          assert(RecordsReader.fromConfiguration("records-reader-test-classpath")(identityR).next() != null)
          ()
        }
      }

      it("loads one or more file resources from the file system when the configuration specifies that source") {
        ignoreOutput {
          assert(RecordsReader.fromConfiguration("records-reader-test-filesystem")(identityR).next() != null)
          ()
        }
      }

      it("loads one or more file resources from URLs when the configuration specifies that source") {
        ignoreOutput {
          assert(RecordsReader.fromConfiguration("records-reader-test-urls")(identityR).next() != null)
          ()
        }
      }
    }

    val initializingMsgFmt1 = s"[INFO] ($className): Reading resources from the %s: %s"
    val initializingMsgFmt2 = s"[INFO] ($className): Initializing from resource %s (index: %d)"
    val kindMsgs = Map(
      RecordsReader.SourceKind.FileSystem -> "file system",
      RecordsReader.SourceKind.CLASSPATH  -> "CLASSPATH",
      RecordsReader.SourceKind.URLs       -> "URLs",
    )
    def rereadTest[T](
      kind: RecordsReader.SourceKind.Value,
      resourcePaths: Array[T],
      makeReader: => RecordsReader[(Int, String)],
      extraPrefixLines: Seq[String] = Nil): Unit = {
      val outMsgs = extraPrefixLines ++ formatInitOutput(resourcePaths, kind, 2)
      expectTrimmedOutput(outMsgs.toSeq) {
        val reader = makeReader
        val actual = (0 until 12).foldLeft(Vector.empty[(Long, (Int, String))]) {
          (v, _) ⇒ v :+ reader.next()
        }
        val expected1 = Vector(
          (1, "one"),
          (2, "two"),
          (3, "three"),
          (4, "four"),
          (5, "five"),
          (6, "six"))
        val expected = (expected1 ++ expected1).zipWithIndex.map { case (tup, i) => ((i + 1).toLong, tup) }
        assert(actual == expected)
        ()
      }
    }

    def badRecordsTest[T](
      kind: RecordsReader.SourceKind.Value,
      pathPrefix: String,
      resourcePaths: Array[T],
      makeReader: => RecordsReader[(Int, String)]): Unit = {
      val outMsgs = formatInitOutput(resourcePaths, kind, 1)
      // A bit fragile hard-coding all these strings, but they exactly match the "bad" input file.
      val fmt = s"[WARN] ($className): ${RecordsReader.parseErrorMessageFormat}"
      val file = s"${pathPrefix}bad-records.csv"
      val errMsgs = Array(
        fmt.format(file, 0, "1,", "1,"),
        fmt.format(file, 1, "two", "two"),
        fmt.format(file, 2, "3three", "3three"),
        fmt.format(file, 3, "java.lang.NumberFormatException: For input string: \"four\"", "four,4"),
        fmt.format(file, 4, "java.lang.NumberFormatException: For input string: \"\"", ",five"))

      expectTrimmedOutput(outMsgs, errMsgs) {
        intercept[RecordsReader.AllRecordsAreBad[_]] {
          val reader = makeReader
          (0 until 5).foreach(_ ⇒ reader.next())
        }
        ()
      }
    }

    def formatInitOutput[T](
      resourcePaths: Array[T],
      kind: RecordsReader.SourceKind.Value,
      repeat: Int): Seq[String] = {

      val outMsgs =
        resourcePaths.zipWithIndex.map {
          case (rp, index) => initializingMsgFmt2.format(rp.toString, index)
        }
      val prefix =
        Seq(initializingMsgFmt1.format(
          kindMsgs(kind), resourcePaths.mkString("[", ", ", "]")))
      (1 to repeat).foldLeft(prefix)((s, _) => s ++ outMsgs)
    }

    describe("File system reader") {
      describe("next") {
        it("Continuously rereads the files until terminated") {
          ignoreOutput {
            rereadTest(
              RecordsReader.SourceKind.FileSystem,
              testGoodRecordsFiles,
              RecordsReader.fromFileSystem[(Int, String)](testGoodRecordsFiles)(intStringTupleCSVParse()))
          }
        }

        it("Prints errors for bad records") {
          ignoreOutput {
            badRecordsTest(
              RecordsReader.SourceKind.FileSystem,
              "pipelinesx/src/test/resources/",
              testBadRecordsFiles,
              RecordsReader.fromFileSystem[(Int, String)](testBadRecordsFiles)(intStringTupleCSVParse()))
          }
        }
      }
    }

    describe("CLASSPATH reader") {
      describe("next") {
        it("Continuously rereads the resource until terminated") {
          ignoreOutput {
            rereadTest(
              RecordsReader.SourceKind.CLASSPATH,
              testGoodRecordsResources,
              RecordsReader.fromClasspath(testGoodRecordsResources)(intStringTupleCSVParse()))
          }
        }

        it("Prints errors for bad records") {
          ignoreOutput {
            badRecordsTest(
              RecordsReader.SourceKind.CLASSPATH,
              "",
              testBadRecordsResources,
              RecordsReader.fromClasspath(testBadRecordsResources)(intStringTupleCSVParse()))
          }
        }
      }
    }

    describe("Configuration reader") {
      describe("next") {
        it("Continuously rereads the resource until terminated") {
          ignoreOutput {
            rereadTest(
              RecordsReader.SourceKind.CLASSPATH,
              testGoodRecordsResources,
              RecordsReader.fromConfiguration("records-reader-test-classpath2")(intStringTupleCSVParse()),
              Seq("[INFO] (pipelinesx.ingress.RecordsReaderTest): Determining where to find resources from the configuration at key: records-reader-test-classpath2"))
          }
        }
      }
    }
  }
}

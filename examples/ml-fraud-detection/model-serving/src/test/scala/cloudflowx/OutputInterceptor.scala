import java.io.{ByteArrayOutputStream, PrintStream}

/**
 * Mixin trait for tests to capture stdout and stderr, by intercepting Java's
 * System.out and System.err, then assert the contents are what's expected (or
 * ignore them).
 * Known limitations:
 * 1. Does not successfully capture output in all cases, including Java
 *    logging libraries that are initialized first, even when configured to write
 *    to the console, and possibly some multi-threaded apps. Hence, this class
 *    has limited power.
 * 2. When run with sbt interactively, _without_ `fork := true`, it appears that
 *    stdout isn't always restored properly, so subsequent sbt prompts and the
 *    commands you type are no longer echoed (but still work otherwise)! So, either
 *    set `fork := true` or restart sbt when this happens.
 */
trait OutputInterceptor {

  // Set this to true in a test to add the debug print statements below:
  var dumpOutputStreams: Boolean = false

  protected val savesSystemOut = System.out // JDK
  protected val savesSystemErr = System.err // JDK

  /**
   * Force rest to the default streams.
   * Because there appear to be cases where this doesn't always happen in a test,
   * it's recommended to use this with a test-wide "after" method, like the
   * ScalaTest "BeforeAndAfterAll.afterAll" method.
   */
  def resetOutputs(): Unit = {
    System.setOut(savesSystemOut)
    System.setErr(savesSystemErr)
  }

  protected lazy val testClassName = getClass().getName()

  /**
   * Simply wrap a thunk to capture and ignore all stdout and stderr output,
   * _if_ the test passes, otherwise print the accumulated output.
   * @param test logic to execute.
   */
  def ignoreOutput(test: ⇒ Unit) = doCheckOutput(false)(test) { (_, _) ⇒ () }

  /**
   * Wrap a thunk to capture all stdout output, then assert it has exactly
   * the expected content. Output to stderr is ignored.
   * @param expectedOutLines sequence of lines expected, verbatim. Pass [[OutputInterceptor.empty]] if you expect NO output.
   * @param test logic to execute.
   */
  def expectOutput(expectedOutLines: Seq[String])(
      test: ⇒ Unit) = doCheckOutput(false)(test)(defaultCheck(true, expectedOutLines, OutputInterceptor.empty))

  /**
   * Wrap a thunk to capture all stdout and stderr output, then assert they have
   * exactly the expected content.
   * @param expectedOutLines sequence of lines expected, verbatim. Pass [[OutputInterceptor.empty]] if you expect NO output.
   * @param expectedErrLines sequence of lines expected, verbatim. Pass [[OutputInterceptor.empty]] if you expect NO output.
   * @param test logic to execute.
   */
  def expectOutput(
      expectedOutLines: Seq[String],
      expectedErrLines: Seq[String])(
      test: ⇒ Unit) = doCheckOutput(false)(test)(defaultCheck(false, expectedOutLines, expectedErrLines))

  /**
   * Wrap a thunk to capture all stdout output, then assert it has exactly the
   * expected content, after trimming leading and trailing whitespace on each line
   * and removing blank lines. (The expected strings are also trimmed this way).
   * @param expectedOutLines sequence of lines expected, verbatim. Pass [[OutputInterceptor.empty]] if you expect NO output.
   * @param test logic to execute.
   */
  def expectTrimmedOutput(
      expectedOutLines: Seq[String])(
      test: ⇒ Unit) = doCheckOutput(true)(test)(defaultCheck(true, expectedOutLines, OutputInterceptor.empty))

  /**
   * Wrap a thunk to capture all stdout and stderr output, then assert they have
   * exactly the expected content, after trimming leading and trailing whitespace
   * on each line and removing blank lines. (The expected strings are also trimmed
   * this way).
   * @param expectedOutLines sequence of lines expected, verbatim. Pass [[OutputInterceptor.empty]] if you expect NO output.
   * @param expectedErrLines sequence of lines expected, verbatim. Pass [[OutputInterceptor.empty]] if you expect NO output.
   * @param test logic to execute.
   */
  def expectTrimmedOutput(
      expectedOutLines: Seq[String],
      expectedErrLines: Seq[String])(
      test: ⇒ Unit) = doCheckOutput(true)(test)(defaultCheck(false, expectedOutLines, expectedErrLines))

  /**
   * Wrap a test to capture and all stdout and stderr output, then apply the
   * user-specified test function on the content. Use this function, for example,
   * when you want to check that some lines contain certain content, but ignore
   * everything else. The function will be passed a `Seq[String]` of the captured
   * stdout and a `Seq[String]` of the captured stderr. The function should perform
   * the required checks and assert/fail on error.
   * @param test logic to execute.
   * @param checkActualOutErrLines function to validate the output is correct
   */
  def checkOutput(test: ⇒ Unit)(checkActualOutErrLines: (Seq[String], Seq[String]) ⇒ Unit) =
    doCheckOutput(false)(test)(checkActualOutErrLines)

  /**
   * Wrap a test to capture and all stdout and stderr output, trim leading and
   * trailing whitespace on each line and remove blank lines, then apply the
   * user-specified test function on the content. Use this function, for example,
   * when you want to check that some lines contain certain content, but ignore
   * everything else. The function will be passed a `Seq[String]` of the captured
   * stdout and a `Seq[String]` of the captured stderr. The function should perform
   * the required checks and assert/fail on error.
   * @param test logic to execute.
   * @param checkActualOutErrLines function to validate the output is correct
   */
  def checkTrimmedOutput(test: ⇒ Unit)(checkActualOutErrLines: (Seq[String], Seq[String]) ⇒ Unit) =
    doCheckOutput(true)(test)(checkActualOutErrLines)

  protected def doCheckOutput(trim: Boolean)(test: ⇒ Unit)(checkActualOutErrLines: (Seq[String], Seq[String]) ⇒ Unit) {

    val outCapture = new ByteArrayOutputStream
    val errCapture = new ByteArrayOutputStream
    val outPrint = new PrintStream(outCapture)
    val errPrint = new PrintStream(errCapture)

    // Capture _both_ stdout and stderr hooks at the Java and Scala levels.
    try {
      Console.withOut(outCapture) {
        System.setOut(outPrint)
        Console.withErr(errCapture) {
          System.setErr(errPrint)
          test
        }
      }
    } finally {
      // Not sure this is really needed, but there appear to be cases where stdout
      // is not longer shown in a console after the test suite runs.
      resetOutputs()
    }

    val outLines1 = outCapture.toString.split("\n").toSeq
    val outLines = if (trim) outLines1.map(_.trim).filter(_.size >= 0) else outLines1
    val errLines1 = errCapture.toString.split("\n").toSeq
    val errLines = if (trim) errLines1.map(_.trim).filter(_.size >= 0) else errLines1
    val trimMsg = if (trim) " (trimmed)" else ""
    if (dumpOutputStreams) {
      println(outLines.mkString(s"$testClassName - captured stdout$trimMsg:\n  ", "\n  ", "\n"))
      println(errLines.mkString(s"$testClassName - captured stderr$trimMsg:\n  ", "\n  ", "\n"))
    }
    outPrint.close()
    errPrint.close()
    checkActualOutErrLines(outLines, errLines)
  }

  protected def dumpCapturedOutput(outStrings: String, errStrings: String): Unit =
    println(s"""
      |captured stdout:
      |  ${outStrings.replaceAll("\n", "\n  ")}
      |
      |captured stderr:
      |  ${errStrings.replaceAll("\n", "\n  ")}
      |
      |""".stripMargin)

  /**
   * Construct the default checker, which compares the actual with expected output,
   * verbatim. When trimming is request, it is done by [[doCheckOutput]] before
   * calling the function returned by this method.
   */
  protected def defaultCheck(
      ignoreErrOutput:  Boolean,
      expectedOutLines: Seq[String],
      expectedErrLines: Seq[String]): (Seq[String], Seq[String]) ⇒ Unit =
    (outLines, errLines) ⇒ {
      diffLines("out", outLines, expectedOutLines)
      if (ignoreErrOutput == false) {
        diffLines("err", errLines, expectedErrLines)
      }
    }

  protected def diffLines(
      label:    String,
      actual:   Seq[String],
      expected: Seq[String]): Unit = {
    assert(
      actual.size == expected.size, sizeDiffString(label, actual, expected))
    actual.zip(expected).zipWithIndex.foreach {
      case ((a, e), i) ⇒
        assert(a == e, notEqualDiffString(label, i, a, e, actual, expected))
    }
  }

  private def sizeDiffString(label: String, actual: Seq[String], expected: Seq[String]): String =
    diffString(s"$label - size mismatch: ${actual.size} != ${expected.size}", actual, expected)

  private def notEqualDiffString(
      label:        String,
      index:        Int,
      actualLine:   String,
      expectedLine: String,
      actual:       Seq[String],
      expected:     Seq[String]): String =
    diffString(s"$label - mismatch at line $index: $actualLine != $expectedLine", actual, expected)

  private def diffString(prefix: String, actual: Seq[String], expected: Seq[String]): String = {
    val sb = new StringBuilder()
    sb.append(s"${testClassName} - ${prefix}\n")
    sb.append("Actual =?= Expected (Note: Seq() looks empty, but could be Seq(\"\")):\n")
    actual.zip(expected).zipWithIndex.foreach {
      case ((a, e), i) ⇒
        sb.append("%4d: %s =?= %s\n".format(i, a, e))
    }
    sb.toString
  }
}

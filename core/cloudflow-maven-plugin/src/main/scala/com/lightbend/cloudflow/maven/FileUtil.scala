/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.cloudflow.maven

import java.io.{ File, PrintWriter }
import scala.io.Source

object FileUtil {

  def readLines(f: File) = {
    Source.fromFile(f).getLines().toList
  }

  def writeFile(f: File, content: String) = {
    val pw = new PrintWriter(f)
    try {
      pw.write(content)
      pw.flush()
    } finally {
      pw.close()
    }
  }

}

package common

import java.io._

object FileUtils {

  def dumpToFile(fileName: String, content: String) = {
    val file = new File(fileName)
    if (file.exists()) file.delete()
    val pw = new PrintWriter(new File(fileName))
    pw.write(content)
    pw.close
  }

}

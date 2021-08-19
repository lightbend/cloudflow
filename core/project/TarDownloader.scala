import java.io.{ File, FileOutputStream }
import java.net.URL

import org.apache.commons.io.IOUtils
import org.codehaus.plexus.archiver.tar.TarGZipUnArchiver
import org.codehaus.plexus.logging.Logger
import org.codehaus.plexus.logging.console.ConsoleLogger

object TarDownloader {
  private val unArchiverConsole = new ConsoleLogger(Logger.LEVEL_INFO, "TarUnarchive")

  private def getTarFromUrl(url: URL, to: File): File = {
    val downloadFileStream = new FileOutputStream(to)
    IOUtils.copyLarge(url.openStream(), downloadFileStream)
    downloadFileStream.flush()
    to
  }

  private def extract(src: File, to: File): Unit = {
    val unArchiver = new TarGZipUnArchiver(src)
    unArchiver.setDestDirectory(to)
    unArchiver.enableLogging(unArchiverConsole)
    unArchiver.extract()
  }

  def downloadAndExtract(from: URL, to: File): File = {
    val tmpTarFile = File.createTempFile("tar", ".tar.gz")
    try {
      getTarFromUrl(from, tmpTarFile)

      to.mkdirs()
      extract(tmpTarFile, to)
      to
    } finally {
      // delete downloaded tmp file at the end
      tmpTarFile.delete()
    }
  }
}

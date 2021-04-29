import akka.cli.cloudflow.commands.Configure
import org.scalatest._
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should._

import java.io.File

import ItSetup._

class ItLogbackSpec extends ItDefaultSpec("hello-world-logback") {

  "LogbackSpec" - {
    "reconfigure an application with a different logback.xml file" in {
      // Arrange
      val cmd = Configure(cloudflowApp = appName, logbackConfig = Some(new File("it-test/src/test/resources/example-logback")))

      // Act
      configureApp() { _ => cli.run(cmd) }

      // Assert
      eventually {
        withRunningApp { status =>
          streamletPodLog(status, "hello-world") should include("LOGGING IS EASY - ERROR hello, world!")
        }
      }
    }
  }

}

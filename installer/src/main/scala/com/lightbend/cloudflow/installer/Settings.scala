package cloudflow.installer

import akka.actor._
import com.typesafe.config._

object Settings extends ExtensionId[Settings] with ExtensionIdProvider {
  override def lookup                                       = Settings
  override def createExtension(system: ExtendedActorSystem) = new Settings(system.settings.config)

  override def apply(system: ActorSystem) =
    new Settings(system.settings.config)
  val root = "cloudflow.installer"

  private def getNonEmptyString(config: Config, key: String) = {
    val value = config.getString(key).trim()
    if (value.nonEmpty) value
    else throw new ConfigException.BadValue(key, s"Should be a non-empty String")
  }

  private def getPort(config: Config, key: String) =
    validatePortnumber(config.getInt(key), key)

  private def validatePortnumber(port: Int, key: String) =
    if (port >= 0 && port <= 65535) port
    else throw new ConfigException.BadValue(key, s"Not a valid port number: $port")
}

final case class Settings(config: Config) extends Extension {
  import Settings._

  val releaseVersion = getNonEmptyString(config, s"$root.release-version")

  val executionTimeout = config.getInt(s"$root.execution-timeout")

  val api = ApiSettings(
    getNonEmptyString(config, s"$root.bind-interface"),
    getPort(config, s"$root.bind-port")
  )

}
final case class ApiSettings(bindInterface: String, bindPort: Int)

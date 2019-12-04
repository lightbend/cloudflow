package pipelinesx.config

import com.typesafe.config.{ Config, ConfigFactory }
import scala.collection.JavaConverters._

final case class ConfigUtil(config: Config) {

  /**
   * Get the value of the correct type from the specified Typesafe Config object.
   * If the type is wrong, throws ConfigException.WrongType.
   * If a value is missing for the key, returns the default value.
   * @return the value if found or the alternative value
   */
  def getOrElse[T](key: String)(orElse: ⇒ T)(implicit getter: (Config, String) ⇒ T): T =
    if (config.hasPath(key)) getter(config, key) else orElse

  /**
   * Get the value of the correct type from the specified Typesafe Config object.
   * If the type is wrong, throws ConfigException.WrongType.
   * If a value is missing for the key, throws [[ConfigUtil.UnknownKey]].
   * Use this method when there is no way to recover from a missing configuration setting.
   * @return the value or throw an exception
   */
  def getOrFail[T](key: String, extraMessage: String = "")(implicit getter: (Config, String) ⇒ T): T =
    if (config.hasPath(key)) getter(config, key)
    else {
      throw ConfigUtil.UnknownKey(key, config, extraMessage)
    }

  /**
   * Get the value of the correct type from the specified Typesafe Config object.
   * If the type is wrong, ConfigException.WrongType is thrown.
   * If a value is missing for the key, returns None.
   * @return the value wrapped in a Some().
   */
  def get[T](key: String)(implicit getter: (Config, String) ⇒ T): Option[T] =
    if (config.hasPath(key)) Some(getter(config, key)) else None

  def toSeq: Seq[(String, Any)] =
    config.entrySet.asScala.toVector.map(entry ⇒ (entry.getKey, entry.getValue))

  /** Default format is compact. */
  override def toString: String = toStringWithFormatting("", "", " ", "->", "")
  /** Flexible formatting */
  def toStringWithFormatting(
      left:          String = "{\n",
      entryIndent:   String = "  ",
      entryDelim:    String = ",\n",
      keyValueDelim: String = " -> ",
      right:         String = "\n}") = {

    val s = new StringBuilder
    s.append(left)
    val seq = this.toSeq
    val len = seq.size
    seq.zipWithIndex.foreach {
      case ((key, value), index) ⇒
        s.append(entryIndent)
        s.append(key)
        s.append(keyValueDelim)
        s.append(value)
        if (index < len - 1) s.append(entryDelim)
    }
    s.append(right)
    s.toString
  }
}

/** TODO: Add more implicit "getters" to cover the rest of the Typesafe Config API. */
object ConfigUtil {

  /**
   * Hook for displaying the full configuration when a key isn't found,
   * for debugging purposes. Because this can be huge, it's off by default.
   */
  var showFullConfig = false

  final case class UnknownKey(key: String, config: Config, message: String = "")
    extends IllegalArgumentException(
      s"Could not find configuration key $key. $message ${fullConfig(config)}")

  private def fullConfig(config: Config): String =
    if (showFullConfig) s"(full config = $config)" else ""

  lazy val defaultConfig: Config = ConfigFactory.load()
  lazy val default: ConfigUtil = new ConfigUtil(defaultConfig)

  object implicits {
    implicit val booleanGetter: (Config, String) ⇒ Boolean = (config, key) ⇒ config.getBoolean(key)
    implicit val intGetter: (Config, String) ⇒ Int = (config, key) ⇒ config.getInt(key)
    implicit val longGetter: (Config, String) ⇒ Long = (config, key) ⇒ config.getLong(key)
    implicit val doubleGetter: (Config, String) ⇒ Double = (config, key) ⇒ config.getDouble(key)
    implicit val stringGetter: (Config, String) ⇒ String = (config, key) ⇒ config.getString(key)

    implicit val booleanListGetter: (Config, String) ⇒ Seq[Boolean] = (config, key) ⇒ config.getBooleanList(key).asScala.toSeq.map(_.booleanValue)
    implicit val intListGetter: (Config, String) ⇒ Seq[Int] = (config, key) ⇒ config.getIntList(key).asScala.toSeq.map(_.intValue)
    implicit val longListGetter: (Config, String) ⇒ Seq[Long] = (config, key) ⇒ config.getLongList(key).asScala.toSeq.map(_.longValue)
    implicit val doubleListGetter: (Config, String) ⇒ Seq[Double] = (config, key) ⇒ config.getDoubleList(key).asScala.toSeq.map(_.doubleValue)
    implicit val stringListGetter: (Config, String) ⇒ Seq[String] = (config, key) ⇒ config.getStringList(key).asScala.toSeq
  }
}

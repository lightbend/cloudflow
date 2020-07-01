/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.streamlets

import cloudflow.streamlets.descriptors._

sealed trait ValidationType {
  def pattern: Option[String] = None
  def `type`: String
}
final case object BooleanValidationType    extends ValidationType { val `type` = "bool"       }
final case object IntegerValidationType    extends ValidationType { val `type` = "int32"      }
final case object DoubleValidationType     extends ValidationType { val `type` = "double"     }
final case object DurationValidationType   extends ValidationType { val `type` = "duration"   }
final case object MemorySizeValidationType extends ValidationType { val `type` = "memorysize" }
final case class RegexpValidationType(regExpPattern: String) extends ValidationType {
  override val pattern = Some(regExpPattern)
  override val `type`  = "string"
}

/**
 * Describes arguments that have to be supplied when deploying a Cloudflow application. Each streamlet in an application can require zero or more arguments.
 * The configuration parameter contains information for the end user entering the values and logic to validate those values so they are entered correctly.
 *
 * Streamlet developers can create their own configuration parameters by deriving from [[ConfigParameter]], the parameter can use validation logic from a fixed set of types derived from [[ValidationType]].
 *
 * The developer can also re-use any of the following predefined configuration parameter types:
 *
 *  - [[IntegerConfigParameter]]
 *  - [[StringConfigParameter]]
 *  - [[DoubleConfigParameter]]
 *  - [[BooleanConfigParameter]]
 *  - [[RegExpConfigParameter]]
 *  - [[DurationConfigParameter]]
 *  - [[MemorySizeConfigParameter]]
 *
 * Each of these pre-defined parameters uses one of the following validation types:
 *
 *  - [[BooleanValidationType]]
 *  - [[IntegerValidationType]]
 *  - [[DoubleValidationType]]
 *  - [[RegexpValidationType]]
 *  - [[DurationValidationType]]
 *  - [[MemorySizeValidationType]]
 *
 * Example of how to create a custom configuration parameter:
 * {{{
 * final case class MilitaryTimeParameter(key: String, defaultValue: Option[String] = None) extends ConfigParameter[String] {
 *  val description: String = "This parameter validates that the users enter the time in 24h format."
 *  val validation = RegexpValidation("^(0[0-9]|1[0-9]|2[0-3]|[0-9]):[0-5][0-9]$")
 *  def toDescriptor = ConfigParameterDescriptor(key,description,validation,defaultValue)
 * }
 * }}}
 *
 * Example on how to use a configuration parameter in a streamlet:
 * {{{
 * class RecordSumFlow extends AkkaStreamlet {
 *   val recordsInWindowParameter = IntegerConfigParameter("records-in-window","This value describes how many records of data should be processed together, default 65 KB", Some(64 * 1024))
 *   override def configParameters = Set(recordsInWindowParameter)
 *
 *   val inlet = AvroInlet[Metric]("metric")
 *   val outlet = AvroOutlet[SummedMetric]("summed-metric")
 *   val shape = StreamletShape.withInlets(inlet).withOutlets(outlet)
 *
 *   def createLogic = new RunnableGraphStreamletLogic() {
 *     val recordsInWindow = streamletConfig.getInt(recordsInWindowParameter.key)
 *     def sumRecords(records: Seq[Metric]) : SummedMetric {....}
 *     def flow = FlowWithOffsetContext[Metric].grouped(recordsInWindow).map(sumRecords)
 *
 *     def runnableGraph() = atLeastOnceSource(inlet).via(flow).to(atLeastOnceSink(outlet))
 *   }
 * }
 * }}}
 */
trait ConfigParameter {

  /**
   * The key that can be used to get a value for this configuration parameter from the streamletConfig
   */
  def key: String

  /**
   * Translates the parameter to a [[cloudflow.streamlets.descriptors.ConfigParameterDescriptor ConfigParameterDescriptor]], it's included in the application descriptor to describe configuration parameter at application deployment time.
   */
  def toDescriptor: ConfigParameterDescriptor

  // Java API
  def getKey() = key
}

/**
 * Describes an integer configuration parameter.
 *
 * @param key name of the parameter
 * @param description description of the parameter
 * @param defaultValue the default value used if the parameter is not specified at application deployment time.
 */
object IntegerConfigParameter {
  // Java API
  def create(key: String, description: String) =
    IntegerConfigParameter(key, description)
}
final case class IntegerConfigParameter(key: String, description: String = "", defaultValue: Option[Int] = None) extends ConfigParameter {
  def toDescriptor: ConfigParameterDescriptor = ConfigParameterDescriptor(key, description, IntegerValidationType, defaultValue)

  /**
   * Java API
   * Gets the value for this configuration parameter.
   */
  def getValue(context: StreamletContext): Int =
    context.streamletConfig.getInt(key)
  def value(implicit context: StreamletContext) =
    context.streamletConfig.getInt(key)
  def withDefaultValue(value: Int) =
    this.copy(defaultValue = Some(value))
}

/**
 * Describes a UTF8 string
 *
 * @param key name of the parameter
 * @param description description of the parameter
 * @param defaultValue the default value used if the parameter is not specified at application deployment time.
 */
object StringConfigParameter {
  // Java API
  def create(key: String, description: String) =
    StringConfigParameter(key, description)
}
final case class StringConfigParameter(key: String, description: String = "", defaultValue: Option[String] = None) extends ConfigParameter {
  def toDescriptor: ConfigParameterDescriptor =
    ConfigParameterDescriptor(key, description, RegexpValidationType(".*"), defaultValue)

  /**
   * Java API
   * Gets the value for this configuration parameter.
   */
  def getValue(context: StreamletContext): String =
    context.streamletConfig.getString(key)

  def value(implicit context: StreamletContext) =
    context.streamletConfig.getString(key)

  def withDefaultValue(value: String) =
    this.copy(defaultValue = Some(value))
}

/**
 * Describes a 64 bit floating point number.
 *
 * @param key name of the parameter
 * @param description description of the parameter
 * @param defaultValue the default value used if the parameter is not specified at application deployment time.
 */
object DoubleConfigParameter {
  // Java API
  def create(key: String, description: String) =
    DoubleConfigParameter(key, description)
}
final case class DoubleConfigParameter(key: String, description: String = "", defaultValue: Option[Double] = None) extends ConfigParameter {
  def toDescriptor: ConfigParameterDescriptor = ConfigParameterDescriptor(key, description, DoubleValidationType, defaultValue)

  /**
   * Java API
   * Gets the value for this configuration parameter.
   */
  def getValue(context: StreamletContext): Double =
    context.streamletConfig.getDouble(key)
  def value(implicit context: StreamletContext) =
    context.streamletConfig.getDouble(key)
  def withDefaultValue(value: Double) =
    this.copy(defaultValue = Some(value))
}

/**
 * Describes a boolean configuration parameter.
 *
 * @param key name of the parameter
 * @param description description of the parameter
 * @param defaultValue the default value used if the parameter is not specified at application deployment time.
 */
object BooleanConfigParameter {
  // Java API
  def create(key: String, description: String) =
    BooleanConfigParameter(key, description)
}
final case class BooleanConfigParameter(key: String, description: String = "", defaultValue: Option[Boolean] = None)
    extends ConfigParameter {
  def toDescriptor: ConfigParameterDescriptor = ConfigParameterDescriptor(key, description, BooleanValidationType, defaultValue)

  /**
   * Java API
   * Gets the value for this configuration parameter.
   */
  def getValue(context: StreamletContext): Boolean =
    context.streamletConfig.getBoolean(key)
  def value(implicit context: StreamletContext) =
    context.streamletConfig.getBoolean(key)
  def withDefaultValue(value: Boolean) =
    this.copy(defaultValue = Some(value))
}

/**
 * Describes a parameter that can be used for custom validation.
 *
 * Note:
 * - This is a limited set of regular expressions compared to regular expressions supported by Java/Scala.
 * - These regular expressions are only used for validation so capture groups are not supported.
 *
 * The type of regular expressions that can be used is described in:
 * [[https://github.com/google/re2/wiki/Syntax]]
 *
 * Example:
 * {{{
 * val durationParameter = RegExpConfigParameter("window-duration","The duration of a sampling window", """((\d{1,2}h\s?)?(\d{1,2}m\s?)?(\d{1,2}s\s?)?)|\d{1,2}""",Some("1m 20s"))
 * }}}
 *
 * @param key name of the parameter
 * @param description description of the parameter
 * @param pattern a regular expression to be used to validate this parameter
 * @param defaultValue the default value used if the parameter is not specified at application deployment time.
 */
object RegExpConfigParameter {
  // Java API
  def create(key: String, description: String, pattern: String) =
    RegExpConfigParameter(key, description, pattern)
}
final case class RegExpConfigParameter(key: String, description: String, pattern: String, defaultValue: Option[String] = None)
    extends ConfigParameter {
  def toDescriptor: ConfigParameterDescriptor = ConfigParameterDescriptor(key, description, RegexpValidationType(pattern), defaultValue)

  /**
   * Java API
   * Gets the value for this configuration parameter.
   */
  def getValue(context: StreamletContext): String =
    context.streamletConfig.getString(key)
  def value(implicit context: StreamletContext) =
    context.streamletConfig.getString(key)
  def withDefaultValue(value: String) =
    this.copy(defaultValue = Some(value))
}

/**
 * Describes a time duration as defined by [[https://github.com/lightbend/config/blob/master/HOCON.md#duration-format]]
 *
 * The following units can be used with [[DurationConfigParameter]]
 *
 * ns, nano, nanos, nanosecond, nanoseconds
 * us, micro, micros, microsecond, microseconds
 * ms, milli, millis, millisecond, milliseconds
 * s, second, seconds
 * m, minute, minutes
 * h, hour, hours
 * d, day, days
 *
 * Example on how to extract a duration value using config:
 * {{{
 * val parameter = DurationDurationConfigParameter("TTL","Time to live of packets sent out","2 m")
 * ...
 * val duration = config.getDuration(parameter.key)
 * }}}
 *
 */
object DurationConfigParameter {
  // Java API
  def create(key: String, description: String): DurationConfigParameter =
    DurationConfigParameter(key, description, None)
}
case class DurationConfigParameter(val key: String, description: String = "", defaultValue: Option[String] = None) extends ConfigParameter {
  def toDescriptor: ConfigParameterDescriptor = ConfigParameterDescriptor(key, description, DurationValidationType, defaultValue)

  /**
   * Java API
   * Gets the value for this configuration parameter.
   */
  def getValue(context: StreamletContext): java.time.Duration =
    context.streamletConfig.getDuration(key)
  def value(implicit context: StreamletContext) =
    context.streamletConfig.getDuration(key)
  def withDefaultValue(value: String) =
    this.copy(defaultValue = Some(value))
}

/**
 * Describes a memory quantity as defined by [[https://github.com/lightbend/config/blob/master/HOCON.md#size-in-bytes-format]]
 *
 * The following units can be used with [[MemorySizeConfigParameter]]
 *
 * "B", "b", "byte", "bytes",
 * "kB", "kilobyte", "kilobytes",
 * "MB", "megabyte", "megabytes",
 * "GB", "gigabyte", "gigabytes",
 * "TB", "terabyte", "terabytes",
 * "PB", "petabyte", "petabytes",
 * "EB", "exabyte", "exabytes",
 * "ZB", "zettabyte", "zettabytes",
 * "YB", "yottabyte", "yottabytes",
 * "K", "k", "Ki", "KiB", "kibibyte", "kibibytes",
 * "M", "m", "Mi", "MiB", "mebibyte", "mebibytes",
 * "G", "g", "Gi", "GiB", "gibibyte", "gibibytes",
 * "T", "t", "Ti", "TiB", "tebibyte", "tebibytes",
 * "P", "p", "Pi", "PiB", "pebibyte", "pebibytes",
 * "E", "e", "Ei", "EiB", "exbibyte", "exbibytes",
 * "Z", "z", "Zi", "ZiB", "zebibyte", "zebibytes",
 * "Y", "y", "Yi", "YiB", "yobibyte", "yobibytes",
 *
 * Example on how to extract a duration value using config:
 * {{{
 * val parameter = MemorySizeMemorySizeConfigParameter("max-size-of-log","Maximum size of log before wrapping","8M")
 * ...
 * val memorySize = config.getMemorySize(parameter.key)
 * }}}
 */
object MemorySizeConfigParameter {
  // Java API
  def create(key: String, description: String) =
    MemorySizeConfigParameter(key, description, None)
}
final case class MemorySizeConfigParameter(key: String, description: String = "", defaultValue: Option[String] = None)
    extends ConfigParameter {
  def toDescriptor: ConfigParameterDescriptor = ConfigParameterDescriptor(key, description, MemorySizeValidationType, defaultValue)

  /**
   * Java API
   * Gets the value for this configuration parameter.
   */
  def getValue(context: StreamletContext) =
    context.streamletConfig.getMemorySize(key)
  def value(implicit context: StreamletContext) =
    context.streamletConfig.getMemorySize(key)
  def withDefaultValue(value: String) =
    this.copy(defaultValue = Some(value))
}

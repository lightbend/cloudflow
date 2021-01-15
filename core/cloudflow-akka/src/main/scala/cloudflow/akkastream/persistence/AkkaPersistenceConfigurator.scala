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

package cloudflow.akkastream.persistence

import com.typesafe.config.{ Config, ConfigFactory }

import net.ceedubs.ficus.Ficus._

import scala.collection.JavaConverters._

object AkkaPersistenceConfigurator {

  private val CASSANDRACONTACTPOINT = "datastax-java-driver.basic.contact-points"
  private val CASSANDRAUSER         = "datastax-java-driver.basic.authentication.username"
  private val CASSANDRAPASSWORD     = "datastax-java-driver.basic.authentication.password"

  private val JDBCPROFILE  = "akka-persistence-jdbc.shared-databases.slick.profile"
  private val JDBCURL      = "akka-persistence-jdbc.shared-databases.slick.db.url"
  private val JDBCUSER     = "akka-persistence-jdbc.shared-databases.slick.db.user"
  private val JDBCPASSWORD = "akka-persistence-jdbc.shared-databases.slick.db.password"
  private val JDBCDRIVER   = "akka-persistence-jdbc.shared-databases.slick.db.driver"

  private val RUNTIMEPERSISTENCE = "cloudflow.runtimes.akka.config.akka.persistence"

  private val CASSANDRAPERSISTENCE = "cassandra"
  private val CASSANDRAPCONTACT    = "contactpoint"
  private val CASSANDRAPUSER       = "user"
  private val CASSANDRAPPASS       = "password"

  private val JDBCPERSISTENCE = "jdbc"
  private val JDBCPPROFILE    = "profile"
  private val JDBCPURL        = "url"
  private val JDBCPDRIVER     = "driver"
  private val JDBCPUSER       = "user"
  private val JDBCPPASS       = "password"

  def addPersistenceConfiguration(baseConfig: Config,
                                  persistence: DBConfiguration,
                                  baseJDBCConfig: String = "jdbcpersistence.conf",
                                  baseCassandraConfig: String = "cassandrapersistence.conf"): Config =
    persistence match {
      case cassandraConfiguration: CassandraConfiguration => // It is cassandra config
        val cMap = Map(
          CASSANDRACONTACTPOINT -> List(cassandraConfiguration.contactpoint).asJava,
          CASSANDRAUSER         -> cassandraConfiguration.user,
          CASSANDRAPASSWORD     -> cassandraConfiguration.passwrd
        )
        ConfigFactory
          .parseMap(cMap.asJava)
          .withFallback(ConfigFactory.parseResourcesAnySyntax(baseCassandraConfig))
          .withFallback(baseConfig)

      case jdbcConfiguration: JDBCConfiguration => // It is JDBC config
        val jdbcMap = Map(
          JDBCPROFILE  -> jdbcConfiguration.profile,
          JDBCURL      -> jdbcConfiguration.url,
          JDBCUSER     -> jdbcConfiguration.user,
          JDBCPASSWORD -> jdbcConfiguration.passwrd,
          JDBCDRIVER   -> jdbcConfiguration.driver
        )
        ConfigFactory
          .parseMap(jdbcMap.asJava)
          .withFallback(ConfigFactory.parseResourcesAnySyntax(baseJDBCConfig))
          .withFallback(baseConfig)
      case _ => baseConfig
    }

  def getPersistenceParams(config: Config, streamletName: String): Option[DBConfiguration] = {

    val STREAMLETPERSISTENCE = s"cloudflow.streamlets.${streamletName}.config.akka.persistence"
    // Try streamlet context first
    config.as[Option[Config]](STREAMLETPERSISTENCE) match {
      case Some(persistence) => // we have definition
        internalGetPersistence(persistence)
      case _ => // try runtime
        config.as[Option[Config]](RUNTIMEPERSISTENCE) match {
          case Some(persistence) => // we have definition
            internalGetPersistence(persistence)
          case _ => None
        }
    }
  }

  private def internalGetPersistence(config: Config): Option[DBConfiguration] =
    // Try cassandra
    config.as[Option[Config]](CASSANDRAPERSISTENCE) match {
      case Some(cpers) => // populate Cassandra
        Some(
          CassandraConfiguration(
            cpers.as[Option[String]](CASSANDRAPCONTACT).getOrElse(""),
            cpers.as[Option[String]](CASSANDRAPUSER).getOrElse(""),
            cpers.as[Option[String]](CASSANDRAPPASS).getOrElse("")
          )
        )
      case _ => // Try JDBC
        config.as[Option[Config]](JDBCPERSISTENCE) match {
          case Some(jpers) => // Populate JDBC
            Some(
              JDBCConfiguration(
                jpers.as[Option[String]](JDBCPPROFILE).getOrElse(""),
                jpers.as[Option[String]](JDBCPURL).getOrElse(""),
                jpers.as[Option[String]](JDBCPDRIVER).getOrElse(""),
                jpers.as[Option[String]](JDBCPUSER).getOrElse(""),
                jpers.as[Option[String]](JDBCPPASS).getOrElse("")
              )
            )
          case _ => None
        }
    }
}

sealed trait DBConfiguration {}

case class CassandraConfiguration(contactpoint: String, user: String, passwrd: String) extends DBConfiguration

case class JDBCConfiguration(profile: String, url: String, driver: String, user: String, passwrd: String) extends DBConfiguration

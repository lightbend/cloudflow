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
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

object AkkaPersistenceConfigurator {

  private val CASSANDRACONTACTPOINT     = "datastax-java-driver.basic.contact-points"
  private val CASSANDRAKEYSPACEJOURNAL  = "akka.persistence.cassandra.journal.keyspace"
  private val CASSANDRAKEYSPACESNAPSHOT = "akka.persistence.cassandra.snapshot.keyspace"
  private val CASSANDRAUSER             = "datastax-java-driver.basic.authentication.username"
  private val CASSANDRAPASSWORD         = "datastax-java-driver.basic.authentication.password"

  private val JDBCPROFILE  = "akka-persistence-jdbc.shared-databases.slick.profile"
  private val JDBCURL      = "akka-persistence-jdbc.shared-databases.slick.db.url"
  private val JDBCUSER     = "akka-persistence-jdbc.shared-databases.slick.db.user"
  private val JDBCPASSWORD = "akka-persistence-jdbc.shared-databases.slick.db.password"
  private val JDBCDRIVER   = "akka-persistence-jdbc.shared-databases.slick.db.driver"

  private val RUNTIMEPERSISTENCE = "cloudflow.runtimes.akka.config.akka.persistence"

  private val DBTYPE   = "dbtype"
  private val HOST     = "dbhost"
  private val PORT     = "dbport"
  private val DATABASE = "database"
  private val USER     = "user"
  private val PASSWORD = "password"

  private val log = LoggerFactory.getLogger(this.getClass)

  def addPersistenceConfiguration(baseConfig: Config,
                                  persistence: DBConfiguration,
                                  baseJDBCConfig: String = "jdbcpersistence.conf",
                                  baseCassandraConfig: String = "cassandrapersistence.conf"): Config =
    persistence match {
      case cassandraConfiguration: CassandraConfiguration => // It is cassandra config
        log.info(s"Building Cassandra configuration from $cassandraConfiguration")
        val cMap = Map(
          CASSANDRACONTACTPOINT     -> List(cassandraConfiguration.contactpoint).asJava,
          CASSANDRAKEYSPACEJOURNAL  -> cassandraConfiguration.keyspace,
          CASSANDRAKEYSPACESNAPSHOT -> cassandraConfiguration.keyspace,
          CASSANDRAUSER             -> cassandraConfiguration.user,
          CASSANDRAPASSWORD         -> cassandraConfiguration.passwrd
        )

        ConfigFactory
          .parseMap(cMap.asJava)
          .withFallback(ConfigFactory.parseResourcesAnySyntax(baseCassandraConfig))
          .withFallback(baseConfig)

      case jdbcConfiguration: JDBCConfiguration => // It is JDBC config
        log.info(s"Building JDBC configuration from $jdbcConfiguration")
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
        internalPopulatePersistence(persistence)
      case _ => // try runtime
        config.as[Option[Config]](RUNTIMEPERSISTENCE) match {
          case Some(persistence) => // we have definition
            internalPopulatePersistence(persistence)
          case _ => None
        }
    }
  }

  private def internalPopulatePersistence(config: Config): Option[DBConfiguration] = {
    val dbType = config.as[Option[String]](DBTYPE).getOrElse("unknown")
    dbType match {
      case db if (db.toLowerCase == "cassandra") => // Cassandra
        val host = config.as[Option[String]](HOST).getOrElse("127.0.0.1")
        val port = config.as[Option[String]](PORT).getOrElse("9042")
        val configuration = CassandraConfiguration(
          s"$host:$port",
          config.as[Option[String]](DATABASE).getOrElse("akkapersistence"),
          config.as[Option[String]](USER).getOrElse("cassandra"),
          config.as[Option[String]](PASSWORD).getOrElse("cassandra")
        )
        log.info(s"Created Cassandra parameters $configuration")
        Some(configuration)
      case db if (db.toLowerCase == "h2") => // h2
        val configuration = JDBCConfiguration(
          "slick.jdbc.H2Profile$",
          "jdbc:h2:mem:test-database;DATABASE_TO_UPPER=false;",
          "org.h2.Driver",
          config.as[Option[String]](USER).getOrElse("root"),
          config.as[Option[String]](PASSWORD).getOrElse("root")
        )
        log.info(s"Created H2 parameters $configuration")
        Some(configuration)
      case db if (db.toLowerCase == "postgres") => // Postgre
        val host     = config.as[Option[String]](HOST).getOrElse("localhost")
        val port     = config.as[Option[String]](PORT).getOrElse("5432")
        val database = config.as[Option[String]](DATABASE).getOrElse("akkapersistence")
        val configuration = JDBCConfiguration(
          "slick.jdbc.PostgresProfile$",
          s"jdbc:postgresql://$host:$port/$database",
          "org.postgresql.Driver",
          config.as[Option[String]](USER).getOrElse("postgres"),
          config.as[Option[String]](PASSWORD).getOrElse("postgres")
        )
        log.info(s"Created Postgres parameters $configuration")
        Some(configuration)
      case db if (db.toLowerCase == "mysql") => // MySQL
        val host     = config.as[Option[String]](HOST).getOrElse("localhost")
        val port     = config.as[Option[String]](PORT).getOrElse("3306")
        val database = config.as[Option[String]](DATABASE).getOrElse("akkapersistence")
        val configuration = JDBCConfiguration(
          "slick.jdbc.MySQLProfile$",
          s"jdbc:mysql://$host:$port/$database?cachePrepStmts=true&cacheCallableStmts=true&cacheServerConfiguration=true&useLocalSessionState=true&elideSetAutoCommits=true&alwaysSendSetIsolation=false&enableQueryTimeouts=false&connectionAttributes=none&verifyServerCertificate=false&useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&useLegacyDatetimeCode=false&serverTimezone=UTC&rewriteBatchedStatements=true",
          "com.mysql.cj.jdbc.Driver",
          config.as[Option[String]](USER).getOrElse("root"),
          config.as[Option[String]](PASSWORD).getOrElse("root")
        )
        log.info(s"Created mySQL parameters $configuration")
        Some(configuration)
      case db if (db.toLowerCase == "oracle") => // ORACLE
        val host     = config.as[Option[String]](HOST).getOrElse("localhost")
        val port     = config.as[Option[String]](PORT).getOrElse("3306")
        val database = config.as[Option[String]](DATABASE).getOrElse("akkapersistence")
        val configuration = JDBCConfiguration(
          "slick.jdbc.OracleProfile$",
          s"jdbc:oracle:thin:@//$host:$port/$database",
          "oracle.jdbc.OracleDriver",
          config.as[Option[String]](USER).getOrElse("system"),
          config.as[Option[String]](PASSWORD).getOrElse("oracle")
        )
        log.info(s"Created Oracle parameters $configuration")
        Some(configuration)
      case db if (db.toLowerCase == "sqlserver") => // SQL server
        val host     = config.as[Option[String]](HOST).getOrElse("localhost")
        val port     = config.as[Option[String]](PORT).getOrElse("1433")
        val database = config.as[Option[String]](DATABASE).getOrElse("akkapersistence")
        val configuration = JDBCConfiguration(
          "slick.jdbc.SQLServerProfile$",
          s"jdbc:sqlserver://$host:$port;databaseName=$database;integratedSecurity=false;",
          "com.microsoft.sqlserver.jdbc.SQLServerDriver",
          config.as[Option[String]](USER).getOrElse("docker"),
          config.as[Option[String]](PASSWORD).getOrElse("docker")
        )
        log.info(s"Created SQL Server parameters $configuration")
        Some(configuration)
      case _ => // Unknown DB type
        log.info(s"Unknown DB type $dbType - skipping")
        None
    }
  }
}

sealed trait DBConfiguration {}

case class CassandraConfiguration(contactpoint: String, keyspace: String, user: String, passwrd: String) extends DBConfiguration

case class JDBCConfiguration(profile: String, url: String, driver: String, user: String, passwrd: String) extends DBConfiguration

/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

import java.io.File
import org.scalatest.time.SpanSugar._

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import scala.io.Source
import scala.util.Using
import io.fabric8.kubernetes.api.model.NamespaceBuilder

trait ItResources {
  val prefix = "./src/it/resources"

  val cr = new File(s"$prefix/swiss-knife.json")
  val updateConfig = new File(s"$prefix/update_config_params.conf")
  val secret = new File(s"$prefix/secret.yaml")
  val secretName = "mysecret"
  val pvc = new File(s"$prefix/pvc.yaml")
  val pvcName = "myclaim"
  val updateMountingSecret = new File(s"$prefix/update_mounting_secret.conf")
  val updateMountingPvc = new File(s"$prefix/update_mounting_pvc.conf")
  val updateAkkaProcessResources = new File(s"$prefix/update_akka_process_resources.conf")
  val updateAkkaRuntimeResources = new File(s"$prefix/update_akka_runtime.conf")
  val updateSparkConfiguration = new File(s"$prefix/update_spark_config.conf")
  val updateAkkaConfiguration = new File(s"$prefix/update_akka_config.conf")
  val defaultConfiguration = new File(s"$prefix/default_config.conf")
  val pvcResourceAkkaFileMountPath = "/tmp/some-akka/file.txt"
  val pvcResourceSparkFileMountPath = "/tmp/some-spark/file.txt"
  val pvcResourceFlinkFileMountPath = "/tmp/some-flink/file.txt"
  val pvcResourceLocal = new File(s"$prefix/imhere.txt")
  val pvcResourceLocalContent = Using(Source.fromFile(pvcResourceLocal)) { _.mkString }.get
  val pvcResourceSpark = new File(s"$prefix/spark-pvc.yaml")
  val pvcResourceFlink = new File(s"$prefix/flink-pvc.yaml")
  val secretFileMountPath = "/tmp/some/password"
  val secretFilePassword = "1f2d1e2e67df"
  private val mult = 1
  val deploySleep = (10 * mult).seconds
  val postConfigurationTimeout = (2 * mult).minutes
  val patience = (5 * mult).minutes
  val interval = (2 * mult).seconds
  val appName = "swiss-knife"
  val namespace = new NamespaceBuilder().withMetadata(new ObjectMetaBuilder().withName(appName).build()).build()
}

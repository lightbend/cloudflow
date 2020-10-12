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

package cloudflow.sbt

import java.lang.reflect.Modifier

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner
import com.typesafe.config._

import scala.collection.JavaConverters._
import scala.util._
import scala.util.control.NoStackTrace

object StreamletScanner {
  val StreamletClassName                   = "cloudflow.streamlets.Streamlet"
  val StreamletDescriptorMethod            = "jsonDescriptor"
  val EmptyParameterTypes: Array[Class[_]] = Array.empty
  val EmptyParameterValues: Array[AnyRef]  = Array.empty

  def scanForStreamletDescriptors(classLoader: ClassLoader, projectId: String): Map[String, Try[Config]] =
    scan(classLoader)
      .map { case (s, c) ⇒ s -> enrichDescriptorWithProjectId(projectId)(c) }

  def enrichDescriptorWithProjectId(projectId: String): Try[Config] ⇒ Try[Config] = { rawStreamlet ⇒
    rawStreamlet.map(_.withValue("project_id", ConfigValueFactory.fromAnyRef(projectId)))
  }

  private[sbt] def scan(classLoader: ClassLoader): Map[String, Try[Config]] = {
    val currentClassLoader = Thread.currentThread.getContextClassLoader
    val scanResult: List[String] = new FastClasspathScanner()
      .addClassLoader(classLoader)
      .scan()
      .getNamesOfSubclassesOf(StreamletClassName)
      .asScala
      .toList
    Thread.currentThread.setContextClassLoader(classLoader)
    val nonAbstractClasses: List[Class[_]] = scanResult.map(nonAbstract(_, classLoader)).collect { case Success(x) ⇒ x }
    val descriptors = nonAbstractClasses
      .map(clazz ⇒ clazz.getName -> getDescriptor(clazz))
      .toMap
    Thread.currentThread.setContextClassLoader(currentClassLoader)
    descriptors
  }

  private def nonAbstract(className: String, classLoader: ClassLoader): Try[Class[_]] =
    loadClass(className, classLoader).filter { clazz ⇒
      !Modifier.isAbstract(clazz.getModifiers)
    }

  private[sbt] def getDescriptor(streamletClass: Class[_]): Try[Config] =
    getInstance(streamletClass)
      .flatMap { streamletInstance ⇒
        val descriptorMethod = streamletClass.getMethod(StreamletDescriptorMethod, EmptyParameterTypes: _*)
        if (descriptorMethod == null) {
          // This should be impossible since any class that matches the Streamlet trait should have this method...
          Failure(DescriptorMethodMissing(streamletClass))
        } else {
          Try {
            val emptyParameterValues: Array[AnyRef] = Array.empty
            val descriptor                          = descriptorMethod.invoke(streamletInstance, emptyParameterValues: _*)

            ConfigFactory.parseString(descriptor.toString)
          }.recoverWith {
            // This should be either impossible or extremely rare since it is our own method we are calling
            case error ⇒ Failure(DescriptorMethodFailure(streamletClass, error))
          }
        }
      }

  private def getInstance(clazz: Class[_]): Try[Any] =
    getInstanceFromScalaObject(clazz).recoverWith {
      case _ ⇒ getInstanceFromDefaultConstructor(clazz)
    }

  private def getInstanceFromDefaultConstructor(streamletClass: Class[_]): Try[Any] =
    if (!hasDefaultConstructor(streamletClass)) {
      Failure(ConstructorMissing(streamletClass))
    } else {
      Try(streamletClass.getDeclaredConstructor().newInstance()).recoverWith {
        case error ⇒ Failure(ConstructorFailure(streamletClass, error))
      }
    }

  private def hasDefaultConstructor(cls: Class[_]) = cls.getConstructors.exists(_.getParameterCount == 0)

  private def getInstanceFromScalaObject(clazz: Class[_]): Try[AnyRef] =
    Try(clazz.getField("MODULE$").get(null))

  private def loadClass(className: String, classLoader: ClassLoader): Try[Class[_]] =
    Try(Class.forName(className, true, classLoader))
}

/**
 * An exception hierarchy to provide feedback to the user that there is an issue with how the Streamlet
 * is defined.
 */
sealed abstract class StreamletScannerException(msg: String) extends RuntimeException(msg) with NoStackTrace

object StreamletScannerException {
  def errorMsg(error: Throwable): String = {
    val cause = error match {
      case e: java.lang.reflect.InvocationTargetException =>
        Option(e.getCause).getOrElse(e)
      case _ => error
    }

    s"""an ${cause.getClass.getSimpleName}${Option(cause.getMessage).map(": " + _).getOrElse("")}"""
  }
}
import StreamletScannerException._

final case class ConstructorMissing(streamletClass: Class[_])
    extends StreamletScannerException(
      s"Streamlet '${streamletClass.getName}' could not be instantiated for introspection. It has no default constructor."
    )
final case class ConstructorFailure(streamletClass: Class[_], error: Throwable)
    extends StreamletScannerException(
      s"Streamlet '${streamletClass.getName}' could not be instantiated for introspection. Its constructor threw ${errorMsg(error)}"
    )
final case class DescriptorMethodMissing(streamletClass: Class[_])
    extends StreamletScannerException(
      s"Streamlet '${streamletClass.getName}' is not usable. It has no descriptor method to call."
    )
final case class DescriptorMethodFailure(streamletClass: Class[_], error: Throwable)
    extends StreamletScannerException(
      s"Streamlet '${streamletClass.getName}' could not be introspected. Its descriptor method threw ${errorMsg(error)}"
    )

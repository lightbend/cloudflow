/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
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

package cloudflow.extractor

import java.lang.reflect.Modifier

import io.github.classgraph.ClassGraph
import com.typesafe.config._

import scala.collection.JavaConverters._
import scala.util._
import scala.util.control.NoStackTrace

object StreamletScanner {
  val StreamletClassName = "cloudflow.streamlets.Streamlet"
  val StreamletDescriptorMethod = "jsonDescriptor"
  val EmptyParameterTypes: Array[Class[_]] = Array.empty
  val EmptyParameterValues: Array[AnyRef] = Array.empty

  def scanForStreamletDescriptors(
      classLoader: ClassLoader,
      projectId: String): Map[String, Either[ExtractProblem, Config]] =
    scan(classLoader)
      .map { case (s, c) => s -> enrichDescriptorWithProjectId(projectId)(c) }

  def enrichDescriptorWithProjectId(
      projectId: String): Either[ExtractProblem, Config] => Either[ExtractProblem, Config] = { rawStreamlet =>
    rawStreamlet.map(_.withValue("project_id", ConfigValueFactory.fromAnyRef(projectId)))
  }

  def scan(classLoader: ClassLoader): Map[String, Either[ExtractProblem, Config]] = {
    val scanResult: List[String] = new ClassGraph()
      .enableClassInfo()
      .addClassLoader(classLoader)
      .scan()
      .getSubclasses(StreamletClassName)
      .getNames()
      .asScala
      .toList

    val originalClassLoader = Thread.currentThread().getContextClassLoader()
    val descriptors =
      try {
        Thread.currentThread().setContextClassLoader(classLoader)
        val nonAbstractClasses: List[Class[_]] =
          scanResult.map(nonAbstract(_, classLoader)).collect { case Success(x) => x }

        nonAbstractClasses
          .map(clazz => clazz.getName -> getDescriptor(clazz))
          .toMap
      } finally {
        Thread.currentThread().setContextClassLoader(originalClassLoader)
      }

    descriptors
  }

  private def nonAbstract(className: String, classLoader: ClassLoader): Try[Class[_]] =
    loadClass(className, classLoader).filter { clazz =>
      !Modifier.isAbstract(clazz.getModifiers)
    }

  private def getDescriptor(streamletClass: Class[_]): Either[ExtractProblem, Config] =
    getInstance(streamletClass)
      .flatMap { streamletInstance =>

        val descriptorMethod = streamletClass.getMethod(StreamletDescriptorMethod, EmptyParameterTypes: _*)
        if (descriptorMethod == null) {
          // This should be impossible since any class that matches the Streamlet trait should have this method...
          Left(DescriptorMethodMissing(streamletClass))
        } else {
          Try {
            val emptyParameterValues: Array[AnyRef] = Array.empty
            val descriptor = descriptorMethod.invoke(streamletInstance, emptyParameterValues: _*)

            ConfigFactory.parseString(descriptor.toString)
          }.toEither.left.map {
            // This should be either impossible or extremely rare since it is our own method we are calling
            case error =>
              DescriptorMethodFailure(streamletClass, error)
          }
        }
      }

  private def getInstance(clazz: Class[_]): Either[ExtractProblem, Any] = {
    getInstanceFromScalaObject(clazz).toEither.left.flatMap {
      case _ => getInstanceFromDefaultConstructor(clazz)
    }
  }

  private def getInstanceFromDefaultConstructor(streamletClass: Class[_]): Either[ExtractProblem, Any] = {
    getNoArgConstructor(streamletClass) match {
      case Some(constructor) =>
        Try(constructor.newInstance()).toEither.left.flatMap {
          case error =>
            Left(ConstructorFailure(streamletClass, error))
        }
      case None =>
        Left(ConstructorMissing(streamletClass))
    }
  }

  private def getNoArgConstructor(cls: Class[_]) = {
    cls.getConstructors.find(_.getParameterCount == 0)
  }

  private def getInstanceFromScalaObject(clazz: Class[_]): Try[AnyRef] =
    Try(clazz.getField("MODULE$").get(null))

  private def loadClass(className: String, classLoader: ClassLoader): Try[Class[_]] =
    Try(Class.forName(className, true, classLoader))

}

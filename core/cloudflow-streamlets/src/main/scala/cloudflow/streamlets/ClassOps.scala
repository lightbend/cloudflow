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

package cloudflow.streamlets

import scala.reflect.ClassTag
import scala.util.{ Failure, Try }

object ClassOps {

  def nameOf[T: ClassTag] = {
    val cls = implicitly[ClassTag[T]]
    cls.runtimeClass.getCanonicalName
  }

  def nameOf(obj: AnyRef): String = {
    val clazz = obj.getClass.getCanonicalName
    if (clazz.endsWith("$")) clazz.dropRight(1) else clazz
  }

  trait ClassInstance {
    def instance(): Try[Any]
    def clazz: Class[_]
  }

  case class Obj(clazz: Class[_]) extends ClassInstance {
    override def instance(): Try[Any] =
      Try(clazz.getField("MODULE$").get(clazz))
  }

  case class Clazz(clazz: Class[_]) extends ClassInstance {
    override def instance(): Try[Any] = Try(clazz.newInstance)
  }

  /**
   * Try to create an instance of the class `name`
   */
  private def instanceFromClass(name: String): Try[Any] =
    for {
      c <- loadClass(name)
      i <- Clazz(c).instance
    } yield i

  /**
   * Try to create a singleton object of name `name`
   */
  private def instanceFromObject(name: String): Try[Any] =
    for {
      c <- loadClass(name + "$")
      i <- Obj(c).instance
    } yield i

  /**
   * Helper method to use a `Try` for handling exceptions
   */
  private def loadClass(className: String): Try[Class[_]] = Try {
    Class.forName(className)
  }

  /**
   * Try to create an instance of a class or share a single object with name `className`.
   */
  def instanceOf(className: String): Try[Any] =
    // name ending with `$` indicates it's a singleton object
    if (className.endsWith("$")) instanceFromObject(className.dropRight(1))
    else {
      // first try to see if it's a class that can be instantiated
      instanceFromClass(className).map(identity).recoverWith {

        // if the instantiation fails, it may be due to a variety of reasons including
        // the fact that the class may not have a no-arg constructor or the name passed
        // is that of a singleton object
        case _: InstantiationException =>
          // try instantiating assuming it's an object
          instanceFromObject(className).map(identity).recoverWith {

            // remember we are in the branch where the class was loaded but we got an
            // `InstantiationException` earlier. If we still cannot load this class, we roll back
            // to the earlier exception
            case _: Exception => Failure(new InstantiationException(className))
          }

        case ex: Exception => Failure(ex)
      }
    }

}

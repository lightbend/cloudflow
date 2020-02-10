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

import scala.util.{ Failure, Try }
import scala.util.control.NoStackTrace

import com.typesafe.config.Config

case class LoadedStreamlet(streamlet: Streamlet[StreamletContext], config: StreamletDefinition)

/**
 * Functions to load a streamlet from its configuration through reflection.
 */
trait StreamletLoader {

  def loadStreamlet(config: Config): Try[LoadedStreamlet] =
    for {
      streamletConfig ← StreamletDefinition.read(config)
      loadedStreamlet ← loadStreamlet(streamletConfig)
    } yield loadedStreamlet

  def loadStreamletClass(streamletClassName: String): Try[Streamlet[StreamletContext]] =
    for {
      instance ← ClassOps.instanceOf(streamletClassName).recoverWith {
        case _: ClassNotFoundException ⇒ Failure(new StreamletClassNotFound(streamletClassName))
        case _: InstantiationException ⇒ Failure(new NoArgsConstructorExpectedException(streamletClassName))
      }
      streamlet ← Try(instance.asInstanceOf[Streamlet[StreamletContext]]).recoverWith {
        case ex: ClassCastException ⇒ Failure(new InvalidStreamletClass(streamletClassName, ex))
      }
    } yield streamlet

  def loadStreamlet(streamletConfig: StreamletDefinition): Try[LoadedStreamlet] =
    loadStreamletClass(streamletConfig.streamletClass).map { streamlet ⇒
      LoadedStreamlet(streamlet, streamletConfig)
    }

  case class StreamletClassNotFound(className: String)
      extends Exception(s"The configured Streamlet class $className not found")
      with NoStackTrace

  case class InvalidStreamletClass(className: String, cause: Exception)
      extends Exception(s"The configured Streamlet class $className is invalid")
      with NoStackTrace

  case class NoArgsConstructorExpectedException(className: String)
      extends Exception(s"The configured Streamlet class $className must have an arg-less constructor")
      with NoStackTrace

}

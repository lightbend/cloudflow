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

import scala.collection.immutable
import scala.annotation.varargs

trait StreamletShape {
  def inlets: immutable.IndexedSeq[Inlet]
  def outlets: immutable.IndexedSeq[Outlet]

  @varargs
  def withInlets(inlet: Inlet, inlets: Inlet*): StreamletShape

  @varargs
  def withOutlets(outlet: Outlet, outlets: Outlet*): StreamletShape
}

private[streamlets] final case class StreamletShapeImpl(
    inlets: immutable.IndexedSeq[Inlet],
    outlets: immutable.IndexedSeq[Outlet]
) extends StreamletShape {

  @varargs
  def withInlets(inlet: Inlet, inlets: Inlet*) = copy(inlets = inlet +: inlets.toIndexedSeq)

  @varargs
  def withOutlets(outlet: Outlet, outlets: Outlet*) = copy(outlets = outlet +: outlets.toIndexedSeq)
}

object StreamletShape {
  def apply(inlet: Inlet): StreamletShape =
    StreamletShapeImpl(immutable.IndexedSeq(inlet), immutable.IndexedSeq())
  def apply(outlet: Outlet): StreamletShape =
    StreamletShapeImpl(immutable.IndexedSeq(), immutable.IndexedSeq(outlet))
  def apply(inlet: Inlet, outlet: Outlet): StreamletShape =
    StreamletShapeImpl(immutable.IndexedSeq(inlet), immutable.IndexedSeq(outlet))

  @varargs
  def withInlets(inlet: Inlet, inlets: Inlet*): StreamletShapeImpl =
    StreamletShapeImpl(inlet +: inlets.toIndexedSeq, immutable.IndexedSeq())

  @varargs
  def withOutlets(outlet: Outlet, outlets: Outlet*): StreamletShapeImpl =
    StreamletShapeImpl(immutable.IndexedSeq(), outlet +: outlets.toIndexedSeq)

  // Java API
  @varargs
  def createWithInlets(inlet: Inlet, inlets: Inlet*): StreamletShapeImpl =
    withInlets(inlet, inlets: _*)

  // Java API
  @varargs
  def createWithOutlets(outlet: Outlet, outlets: Outlet*): StreamletShapeImpl =
    withOutlets(outlet, outlets: _*)
}


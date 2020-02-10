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

package cloudflow.spark.sql

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.{ ColumnName, Encoder, Encoders }

import scala.collection.Map
import scala.language.implicitConversions
import scala.reflect.runtime.universe.TypeTag

/**
 * A collection of implicit methods for converting common Scala objects into `org.apache.spark.sql.Dataset`s.
 *
 * @since 1.6.0
 */
object SQLImplicits extends LowPrioritySQLImplicits {

  /**
   * Converts $"col name" into a `org.apache.spark.sql.Column`.
   *
   * @since 2.0.0
   */
  implicit class StringToColumn(val sc: StringContext) {
    def $(args: Any*): ColumnName =
      new ColumnName(sc.s(args: _*))
  }

  // Primitives

  /** @since 1.6.0 */
  implicit def newIntEncoder: Encoder[Int] = Encoders.scalaInt

  /** @since 1.6.0 */
  implicit def newLongEncoder: Encoder[Long] = Encoders.scalaLong

  /** @since 1.6.0 */
  implicit def newDoubleEncoder: Encoder[Double] = Encoders.scalaDouble

  /** @since 1.6.0 */
  implicit def newFloatEncoder: Encoder[Float] = Encoders.scalaFloat

  /** @since 1.6.0 */
  implicit def newByteEncoder: Encoder[Byte] = Encoders.scalaByte

  /** @since 1.6.0 */
  implicit def newShortEncoder: Encoder[Short] = Encoders.scalaShort

  /** @since 1.6.0 */
  implicit def newBooleanEncoder: Encoder[Boolean] = Encoders.scalaBoolean

  /** @since 1.6.0 */
  implicit def newStringEncoder: Encoder[String] = Encoders.STRING

  /** @since 2.2.0 */
  implicit def newJavaDecimalEncoder: Encoder[java.math.BigDecimal] = Encoders.DECIMAL

  /** @since 2.2.0 */
  implicit def newScalaDecimalEncoder: Encoder[scala.math.BigDecimal] = ExpressionEncoder()

  /** @since 2.2.0 */
  implicit def newDateEncoder: Encoder[java.sql.Date] = Encoders.DATE

  /** @since 2.2.0 */
  implicit def newTimeStampEncoder: Encoder[java.sql.Timestamp] = Encoders.TIMESTAMP

  // Boxed primitives

  /** @since 2.0.0 */
  implicit def newBoxedIntEncoder: Encoder[java.lang.Integer] = Encoders.INT

  /** @since 2.0.0 */
  implicit def newBoxedLongEncoder: Encoder[java.lang.Long] = Encoders.LONG

  /** @since 2.0.0 */
  implicit def newBoxedDoubleEncoder: Encoder[java.lang.Double] = Encoders.DOUBLE

  /** @since 2.0.0 */
  implicit def newBoxedFloatEncoder: Encoder[java.lang.Float] = Encoders.FLOAT

  /** @since 2.0.0 */
  implicit def newBoxedByteEncoder: Encoder[java.lang.Byte] = Encoders.BYTE

  /** @since 2.0.0 */
  implicit def newBoxedShortEncoder: Encoder[java.lang.Short] = Encoders.SHORT

  /** @since 2.0.0 */
  implicit def newBoxedBooleanEncoder: Encoder[java.lang.Boolean] = Encoders.BOOLEAN

  // Seqs

  /**
   * @since 1.6.1
   * @deprecated use [[newSequenceEncoder]]
   */
  def newIntSeqEncoder: Encoder[Seq[Int]] = ExpressionEncoder()

  /**
   * @since 1.6.1
   * @deprecated use [[newSequenceEncoder]]
   */
  def newLongSeqEncoder: Encoder[Seq[Long]] = ExpressionEncoder()

  /**
   * @since 1.6.1
   * @deprecated use [[newSequenceEncoder]]
   */
  def newDoubleSeqEncoder: Encoder[Seq[Double]] = ExpressionEncoder()

  /**
   * @since 1.6.1
   * @deprecated use [[newSequenceEncoder]]
   */
  def newFloatSeqEncoder: Encoder[Seq[Float]] = ExpressionEncoder()

  /**
   * @since 1.6.1
   * @deprecated use [[newSequenceEncoder]]
   */
  def newByteSeqEncoder: Encoder[Seq[Byte]] = ExpressionEncoder()

  /**
   * @since 1.6.1
   * @deprecated use [[newSequenceEncoder]]
   */
  def newShortSeqEncoder: Encoder[Seq[Short]] = ExpressionEncoder()

  /**
   * @since 1.6.1
   * @deprecated use [[newSequenceEncoder]]
   */
  def newBooleanSeqEncoder: Encoder[Seq[Boolean]] = ExpressionEncoder()

  /**
   * @since 1.6.1
   * @deprecated use [[newSequenceEncoder]]
   */
  def newStringSeqEncoder: Encoder[Seq[String]] = ExpressionEncoder()

  /**
   * @since 1.6.1
   * @deprecated use [[newSequenceEncoder]]
   */
  def newProductSeqEncoder[A <: Product: TypeTag]: Encoder[Seq[A]] = ExpressionEncoder()

  /** @since 2.2.0 */
  implicit def newSequenceEncoder[T <: Seq[_]: TypeTag]: Encoder[T] = ExpressionEncoder()

  // Maps
  /** @since 2.3.0 */
  implicit def newMapEncoder[T <: Map[_, _]: TypeTag]: Encoder[T] = ExpressionEncoder()

  /**
   * Notice that we serialize `Set` to Catalyst array. The set property is only kept when
   * manipulating the domain objects. The serialization format doesn't keep the set property.
   * When we have a Catalyst array which contains duplicated elements and convert it to
   * `Dataset[Set[T]]` by using the encoder, the elements will be de-duplicated.
   *
   * @since 2.3.0
   */
  implicit def newSetEncoder[T <: Set[_]: TypeTag]: Encoder[T] = ExpressionEncoder()

  // Arrays

  /** @since 1.6.1 */
  implicit def newIntArrayEncoder: Encoder[Array[Int]] = ExpressionEncoder()

  /** @since 1.6.1 */
  implicit def newLongArrayEncoder: Encoder[Array[Long]] = ExpressionEncoder()

  /** @since 1.6.1 */
  implicit def newDoubleArrayEncoder: Encoder[Array[Double]] = ExpressionEncoder()

  /** @since 1.6.1 */
  implicit def newFloatArrayEncoder: Encoder[Array[Float]] = ExpressionEncoder()

  /** @since 1.6.1 */
  implicit def newByteArrayEncoder: Encoder[Array[Byte]] = Encoders.BINARY

  /** @since 1.6.1 */
  implicit def newShortArrayEncoder: Encoder[Array[Short]] = ExpressionEncoder()

  /** @since 1.6.1 */
  implicit def newBooleanArrayEncoder: Encoder[Array[Boolean]] = ExpressionEncoder()

  /** @since 1.6.1 */
  implicit def newStringArrayEncoder: Encoder[Array[String]] = ExpressionEncoder()

  /** @since 1.6.1 */
  implicit def newProductArrayEncoder[A <: Product: TypeTag]: Encoder[Array[A]] =
    ExpressionEncoder()

  /**
   * An implicit conversion that turns a Scala `Symbol` into a `org.apache.spark.sql.Column`.
   * @since 1.3.0
   */
  implicit def symbolToColumn(s: Symbol): ColumnName = new ColumnName(s.name)

}

/**
 * Lower priority implicit methods for converting Scala objects into `org.apache.spark.sql.Dataset`s.
 * Conflicting implicits are placed here to disambiguate resolution.
 *
 * Reasons for including specific implicits:
 * newProductEncoder - to disambiguate for `List`s which are both `Seq` and `Product`
 */
trait LowPrioritySQLImplicits {

  /** @since 1.6.0 */
  implicit def newProductEncoder[T <: Product: TypeTag]: Encoder[T] = Encoders.product[T]

}

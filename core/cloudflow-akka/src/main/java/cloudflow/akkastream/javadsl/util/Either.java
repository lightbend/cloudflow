/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
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

/*  __    __  __  __    __  ___
 * \  \  /  /    \  \  /  /  __/
 *  \  \/  /  /\  \  \/  /  /
 *   \____/__/  \__\____/__/
 *
 * Copyright 2014-2018 Vavr, http://vavr.io
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

package cloudflow.akkastream.javadsl.util;

import java.io.Serializable;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;

public interface Either<L, R> extends Serializable {

  long serialVersionUID = 1L;

  /**
   * Constructs a {@link Right}
   *
   * @param right The value.
   * @param <L>   Type of left value.
   * @param <R>   Type of right value.
   * @return A new {@code Right} instance.
   */
  static <L, R> Either<L, R> right(R right) {
    return new Right<>(right);
  }

  /**
   * Constructs a {@link Left}
   *
   * @param left The value.
   * @param <L>  Type of left value.
   * @param <R>  Type of right value.
   * @return A new {@code Left} instance.
   */
  static <L, R> Either<L, R> left(L left) {
    return new Left<>(left);
  }

  /**
   * Maps the value of this Either if it is a Right, performs no operation if this is a Left.
   *
   * <pre><code>
   * import static io.vavr.API.*;
   *
   * // = Right("A")
   * Right("a").map(String::toUpperCase);
   *
   * // = Left(1)
   * Left(1).map(String::toUpperCase);
   * </code></pre>
   *
   * Throws NullPointerException if {@code mapper} is null.
   * 
   * @param mapper A mapper
   * @param <U>    Component type of the mapped right value
   * @return a mapped {@code Monad}
   */
  @SuppressWarnings("unchecked")
  default <U> Either<L, U> map(Function<? super R, ? extends U> mapper) {
    Objects.requireNonNull(mapper, "mapper is null");
    if (isRight()) {
      return Either.right(mapper.apply(get()));
    } else {
      return (Either<L, U>) this;
    }
  }

  /**
   * Gets the right value if this is a {@code Right} or throws if this is a {@code Left}.
   *
   * Throws NoSuchElementException if this is a {@code Left}.
   *
   * @return the right value
   */
  R get();

  /**
   * Returns the left value.
   *
   * Throws NoSuchElementException if this is a {@code Right}.
   * 
   * @return The left value.
   */
  L getLeft();

  /**
   * Returns whether this Either is a Left.
   *
   * @return true, if this is a Left, false otherwise
   */
  boolean isLeft();

  /**
   * Returns whether this Either is a Right.
   *
   * @return true, if this is a Right, false otherwise
   */
  boolean isRight();
  String stringPrefix();

  /**
   * The {@code Left} version of an {@code Either}.
   *
   * @param <L> left component type
   * @param <R> right component type
   * @author Daniel Dietrich
   */
  final class Left<L, R> implements Either<L, R>, Serializable {

    private static final long serialVersionUID = 1L;

    private final L value;

    /**
     * Constructs a {@code Left}.
     *
     * @param value a left value
     */
    private Left(L value) {
      this.value = value;
    }

    @Override
    public R get() {
      throw new NoSuchElementException("get() on Left");
    }

    @Override
    public L getLeft() {
      return value;
    }

    @Override
    public boolean isLeft() {
      return true;
    }

    @Override
    public boolean isRight() {
      return false;
    }

    @Override
    public boolean equals(Object obj) {
      return (obj == this) || (obj instanceof Left && Objects.equals(value, ((Left<?, ?>) obj).value));
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(value);
    }

    @Override
    public String stringPrefix() {
      return "Left";
    }

    @Override
    public String toString() {
      return stringPrefix() + "(" + value + ")";
    }
  }

  /**
   * The {@code Right} version of an {@code Either}.
   *
   * @param <L> left component type
   * @param <R> right component type
   * @author Daniel Dietrich
   */
  final class Right<L, R> implements Either<L, R>, Serializable {

    private static final long serialVersionUID = 1L;

    private final R value;

    /**
     * Constructs a {@code Right}.
     *
     * @param value a right value
     */
    private Right(R value) {
      this.value = value;
    }

    @Override
    public R get() {
      return value;
    }

    @Override
    public L getLeft() {
      throw new NoSuchElementException("getLeft() on Right");
    }

    @Override
    public boolean isLeft() {
      return false;
    }

    @Override
    public boolean isRight() {
      return true;
    }

    @Override
    public boolean equals(Object obj) {
      return (obj == this) || (obj instanceof Right && Objects.equals(value, ((Right<?, ?>) obj).value));
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(value);
    }

    @Override
    public String stringPrefix() {
      return "Right";
    }

    @Override
    public String toString() {
      return stringPrefix() + "(" + value + ")";
    }
  }
}

package pipelinesx.config

import org.scalatest.FunSpec
import com.typesafe.config.ConfigException.WrongType

class ConfigUtilTest extends FunSpec {
  import ConfigUtil.UnknownKey
  import ConfigUtil.implicits._

  describe("ConfigUtil") {
    describe("construction") {
      it("takes a config argument") {
        assert(ConfigUtil.default.get[Int]("config-util-test.good.int-item") == Some(123))
      }
    }

    describe("companion object") {
      it("has a default value that loads the entire configuration") {
        assert(ConfigUtil.default.get[Int]("config-util-test.good.int-item") == Some(123))
      }
      it("has a field with default value that loads the entire configuration") {
        assert(ConfigUtil(ConfigUtil.defaultConfig).get[Int]("config-util-test.good.int-item") == Some(123))
      }
    }

      def good_get() = {
        val config = ConfigUtil.default
        assert(config.get[Boolean]("config-util-test.good.boolean-item") == Some(false))
        assert(config.get[Int]("config-util-test.good.int-item") == Some(123))
        assert(config.get[Long]("config-util-test.good.long-item") == Some(123456))
        assert(config.get[Double]("config-util-test.good.double-item") == Some(3.14))
        assert(config.get[String]("config-util-test.good.string-item") == Some("a string"))

        assert(config.get[Seq[Boolean]]("config-util-test.good.boolean-list") == Some(Seq(false, true)))
        assert(config.get[Seq[Int]]("config-util-test.good.int-list") == Some(Seq(1, 2, 3)))
        assert(config.get[Seq[Long]]("config-util-test.good.long-list") == Some(Seq(100, 200, 300)))
        assert(config.get[Seq[Double]]("config-util-test.good.double-list") == Some(Seq(1.1, 2.2, 3.14)))
        assert(config.get[Seq[String]]("config-util-test.good.string-list") == Some(Seq("one", "two", "three")))
      }

    describe("get") {
      it("returns a value of the correct type") {
        good_get()
      }
      it("if the key is found, returns the found value in a Some()") {
        good_get()
      }
      it("if the key is not found, returns None") {
        val config = ConfigUtil.default
        assert(config.get[Boolean]("config-util-test.missing-scalar.boolean-item") == None)
        assert(config.get[Int]("config-util-test.missing-scalar.int-item") == None)
        assert(config.get[Long]("config-util-test.missing-scalar.long-item") == None)
        assert(config.get[Double]("config-util-test.missing-scalar.double-item") == None)
        assert(config.get[String]("config-util-test.missing-scalar.string-item") == None)

        assert(config.get[Seq[Boolean]]("config-util-test.missing-list.boolean-list") == None)
        assert(config.get[Seq[Int]]("config-util-test.missing-list.int-list") == None)
        assert(config.get[Seq[Long]]("config-util-test.missing-list.long-list") == None)
        assert(config.get[Seq[Double]]("config-util-test.missing-list.double-list") == None)
        assert(config.get[Seq[String]]("config-util-test.missing-list.string-list") == None)
      }
      it("if the key is found, but the value has the wrong type, throws ConfigException.WrongType") {
        val config = ConfigUtil.default
        intercept[WrongType] { config.get[Boolean]("config-util-test.bad-type-scalar.boolean-item") }
        intercept[WrongType] { config.get[Int]("config-util-test.bad-type-scalar.int-item") }
        intercept[WrongType] { config.get[Long]("config-util-test.bad-type-scalar.long-item") }
        intercept[WrongType] { config.get[Double]("config-util-test.bad-type-scalar.double-item") }
        intercept[WrongType] { config.get[String]("config-util-test.bad-type-scalar.string-item") }

        intercept[WrongType] { config.get[Seq[Boolean]]("config-util-test.bad-type-list.boolean-list") }
        intercept[WrongType] { config.get[Seq[Int]]("config-util-test.bad-type-list.int-list") }
        intercept[WrongType] { config.get[Seq[Long]]("config-util-test.bad-type-list.long-list") }
        intercept[WrongType] { config.get[Seq[Double]]("config-util-test.bad-type-list.double-list") }
        intercept[WrongType] { config.get[Seq[String]]("config-util-test.bad-type-list.string-list") }

        intercept[WrongType] { config.get[Boolean]("config-util-test.not-a-scalar.boolean-item") }
        intercept[WrongType] { config.get[Int]("config-util-test.not-a-scalar.int-item") }
        intercept[WrongType] { config.get[Long]("config-util-test.not-a-scalar.long-item") }
        intercept[WrongType] { config.get[Double]("config-util-test.not-a-scalar.double-item") }
        intercept[WrongType] { config.get[String]("config-util-test.not-a-scalar.string-item") }

        intercept[WrongType] { config.get[Seq[Boolean]]("config-util-test.not-a-list.boolean-list") }
        intercept[WrongType] { config.get[Seq[Int]]("config-util-test.not-a-list.int-list") }
        intercept[WrongType] { config.get[Seq[Long]]("config-util-test.not-a-list.long-list") }
        intercept[WrongType] { config.get[Seq[Double]]("config-util-test.not-a-list.double-list") }
        intercept[WrongType] { config.get[Seq[String]]("config-util-test.not-a-list.string-list") }
      }
    }

      def good_getOrElse() = {
        val config = ConfigUtil.default
        assert(config.getOrElse[Boolean]("config-util-test.good.boolean-item")(true) == false)
        assert(config.getOrElse[Int]("config-util-test.good.int-item")(321) == 123)
        assert(config.getOrElse[Long]("config-util-test.good.long-item")(654321) == 123456)
        assert(config.getOrElse[Double]("config-util-test.good.double-item")(2.17) == 3.14)
        assert(config.getOrElse[String]("config-util-test.good.string-item")("unknown") == "a string")

        assert(config.getOrElse[Seq[Boolean]]("config-util-test.good.boolean-list")(Seq(true, true)) == Seq(false, true))
        assert(config.getOrElse[Seq[Int]]("config-util-test.good.int-list")(Seq(321, 456)) == Seq(1, 2, 3))
        assert(config.getOrElse[Seq[Long]]("config-util-test.good.long-list")(Seq(654321, 1)) == Seq(100, 200, 300))
        assert(config.getOrElse[Seq[Double]]("config-util-test.good.double-list")(Seq(2.17, 1.1)) == Seq(1.1, 2.2, 3.14))
        assert(config.getOrElse[Seq[String]]("config-util-test.good.string-list")(Seq("unknown", "known")) == Seq("one", "two", "three"))
      }

    describe("getOrElse") {
      it("returns a value of the correct type") {
        good_getOrElse()
      }
      it("if the key is found, returns the found value") {
        good_getOrElse()
      }
      it("if the key is not found, returns the 'orElse' value specified") {
        val config = ConfigUtil.default
        assert(config.getOrElse[Boolean]("config-util-test.missing-scalar.boolean-item")(true) == true)
        assert(config.getOrElse[Int]("config-util-test.missing-scalar.int-item")(321) == 321)
        assert(config.getOrElse[Long]("config-util-test.missing-scalar.long-item")(654321) == 654321)
        assert(config.getOrElse[Double]("config-util-test.missing-scalar.double-item")(2.17) == 2.17)
        assert(config.getOrElse[String]("config-util-test.missing-scalar.string-item")("unknown") == "unknown")

        assert(config.getOrElse[Seq[Boolean]]("config-util-test.missing-list.boolean-list")(Seq(true, true)) == Seq(true, true))
        assert(config.getOrElse[Seq[Int]]("config-util-test.missing-list.int-list")(Seq(321, 456)) == Seq(321, 456))
        assert(config.getOrElse[Seq[Long]]("config-util-test.missing-list.long-list")(Seq(654321, 1)) == Seq(654321, 1))
        assert(config.getOrElse[Seq[Double]]("config-util-test.missing-list.double-list")(Seq(2.17, 1.1)) == Seq(2.17, 1.1))
        assert(config.getOrElse[Seq[String]]("config-util-test.missing-list.string-list")(Seq("unknown", "known")) == Seq("unknown", "known"))
      }
      it("if the key is found, but the value has the wrong type, throws ConfigException.WrongType") {
        val config = ConfigUtil.default
        intercept[WrongType] { config.getOrElse[Boolean]("config-util-test.bad-type-scalar.boolean-item")(false) }
        intercept[WrongType] { config.getOrElse[Int]("config-util-test.bad-type-scalar.int-item")(1) }
        intercept[WrongType] { config.getOrElse[Long]("config-util-test.bad-type-scalar.long-item")(1) }
        intercept[WrongType] { config.getOrElse[Double]("config-util-test.bad-type-scalar.double-item")(1.0) }
        intercept[WrongType] { config.getOrElse[String]("config-util-test.bad-type-scalar.string-item")("string") }

        intercept[WrongType] { config.getOrElse[Seq[Boolean]]("config-util-test.bad-type-list.boolean-list")(Seq(false)) }
        intercept[WrongType] { config.getOrElse[Seq[Int]]("config-util-test.bad-type-list.int-list")(Seq(1)) }
        intercept[WrongType] { config.getOrElse[Seq[Long]]("config-util-test.bad-type-list.long-list")(Seq(1)) }
        intercept[WrongType] { config.getOrElse[Seq[Double]]("config-util-test.bad-type-list.double-list")(Seq(1.0)) }
        intercept[WrongType] { config.getOrElse[Seq[String]]("config-util-test.bad-type-list.string-list")(Seq("string")) }

        intercept[WrongType] { config.getOrElse[Boolean]("config-util-test.not-a-scalar.boolean-item")(false) }
        intercept[WrongType] { config.getOrElse[Int]("config-util-test.not-a-scalar.int-item")(1) }
        intercept[WrongType] { config.getOrElse[Long]("config-util-test.not-a-scalar.long-item")(1) }
        intercept[WrongType] { config.getOrElse[Double]("config-util-test.not-a-scalar.double-item")(1.0) }
        intercept[WrongType] { config.getOrElse[String]("config-util-test.not-a-scalar.string-item")("string") }

        intercept[WrongType] { config.getOrElse[Seq[Boolean]]("config-util-test.not-a-list.boolean-list")(Seq(false)) }
        intercept[WrongType] { config.getOrElse[Seq[Int]]("config-util-test.not-a-list.int-list")(Seq(1)) }
        intercept[WrongType] { config.getOrElse[Seq[Long]]("config-util-test.not-a-list.long-list")(Seq(1)) }
        intercept[WrongType] { config.getOrElse[Seq[Double]]("config-util-test.not-a-list.double-list")(Seq(1.0)) }
        intercept[WrongType] { config.getOrElse[Seq[String]]("config-util-test.not-a-list.string-list")(Seq("string")) }
      }
    }

      def good_getOrFail() = {
        val config = ConfigUtil.default
        assert(config.getOrFail[Boolean]("config-util-test.good.boolean-item") == false)
        assert(config.getOrFail[Int]("config-util-test.good.int-item") == 123)
        assert(config.getOrFail[Long]("config-util-test.good.long-item") == 123456)
        assert(config.getOrFail[Double]("config-util-test.good.double-item") == 3.14)
        assert(config.getOrFail[String]("config-util-test.good.string-item") == "a string")

        assert(config.getOrFail[Seq[Boolean]]("config-util-test.good.boolean-list") == Seq(false, true))
        assert(config.getOrFail[Seq[Int]]("config-util-test.good.int-list") == Seq(1, 2, 3))
        assert(config.getOrFail[Seq[Long]]("config-util-test.good.long-list") == Seq(100, 200, 300))
        assert(config.getOrFail[Seq[Double]]("config-util-test.good.double-list") == Seq(1.1, 2.2, 3.14))
        assert(config.getOrFail[Seq[String]]("config-util-test.good.string-list") == Seq("one", "two", "three"))
      }

    describe("getOrFail") {
      it("returns a value of the correct type") {
        good_getOrFail()
      }
      it("if the key is found, returns the found value") {
        good_getOrFail()
      }
      it("if the key is not found, throws ConfigUtil.UnknownKey") {
        val config = ConfigUtil.default
        intercept[UnknownKey] { config.getOrFail[Boolean]("config-util-test.missing-scalar.boolean-item") }
        intercept[UnknownKey] { config.getOrFail[Int]("config-util-test.missing-scalar.int-item") }
        intercept[UnknownKey] { config.getOrFail[Long]("config-util-test.missing-scalar.long-item") }
        intercept[UnknownKey] { config.getOrFail[Double]("config-util-test.missing-scalar.double-item") }
        intercept[UnknownKey] { config.getOrFail[String]("config-util-test.missing-scalar.string-item") }

        intercept[UnknownKey] { config.getOrFail[Seq[Boolean]]("config-util-test.missing-list.boolean-list") }
        intercept[UnknownKey] { config.getOrFail[Seq[Int]]("config-util-test.missing-list.int-list") }
        intercept[UnknownKey] { config.getOrFail[Seq[Long]]("config-util-test.missing-list.long-list") }
        intercept[UnknownKey] { config.getOrFail[Seq[Double]]("config-util-test.missing-list.double-list") }
        intercept[UnknownKey] { config.getOrFail[Seq[String]]("config-util-test.missing-list.string-list") }
      }
      it("if the key is found, but the value has the wrong type, throws ConfigException.WrongType") {
        val config = ConfigUtil.default
        intercept[WrongType] { config.getOrFail[Boolean]("config-util-test.bad-type-scalar.boolean-item") }
        intercept[WrongType] { config.getOrFail[Int]("config-util-test.bad-type-scalar.int-item") }
        intercept[WrongType] { config.getOrFail[Long]("config-util-test.bad-type-scalar.long-item") }
        intercept[WrongType] { config.getOrFail[Double]("config-util-test.bad-type-scalar.double-item") }
        intercept[WrongType] { config.getOrFail[String]("config-util-test.bad-type-scalar.string-item") }

        intercept[WrongType] { config.getOrFail[Seq[Boolean]]("config-util-test.bad-type-list.boolean-list") }
        intercept[WrongType] { config.getOrFail[Seq[Int]]("config-util-test.bad-type-list.int-list") }
        intercept[WrongType] { config.getOrFail[Seq[Long]]("config-util-test.bad-type-list.long-list") }
        intercept[WrongType] { config.getOrFail[Seq[Double]]("config-util-test.bad-type-list.double-list") }
        intercept[WrongType] { config.getOrFail[Seq[String]]("config-util-test.bad-type-list.string-list") }

        intercept[WrongType] { config.getOrFail[Boolean]("config-util-test.not-a-scalar.boolean-item") }
        intercept[WrongType] { config.getOrFail[Int]("config-util-test.not-a-scalar.int-item") }
        intercept[WrongType] { config.getOrFail[Long]("config-util-test.not-a-scalar.long-item") }
        intercept[WrongType] { config.getOrFail[Double]("config-util-test.not-a-scalar.double-item") }
        intercept[WrongType] { config.getOrFail[String]("config-util-test.not-a-scalar.string-item") }

        intercept[WrongType] { config.getOrFail[Seq[Boolean]]("config-util-test.not-a-list.boolean-list") }
        intercept[WrongType] { config.getOrFail[Seq[Int]]("config-util-test.not-a-list.int-list") }
        intercept[WrongType] { config.getOrFail[Seq[Long]]("config-util-test.not-a-list.long-list") }
        intercept[WrongType] { config.getOrFail[Seq[Double]]("config-util-test.not-a-list.double-list") }
        intercept[WrongType] { config.getOrFail[Seq[String]]("config-util-test.not-a-list.string-list") }
      }
    }
  }
}

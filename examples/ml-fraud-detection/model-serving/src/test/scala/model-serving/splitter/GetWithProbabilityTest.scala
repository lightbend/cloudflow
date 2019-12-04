package com.lightbend.modelserving.splitter

import org.scalatest.FlatSpec
import model.splitter.GetWithProbability

class GetWithProbabilityTest extends FlatSpec {

  val probability = 70
  val probabilities = Array(90, 10)

  "An amount of picked item" should "be close to probability" in {
    var accepted = 0
    1 to 100 foreach (i ⇒ {
      GetWithProbability.toUse(probability) match {
        case true ⇒ accepted = accepted + 1
        case _    ⇒
      }
    })
    println(accepted)
    assert((accepted - probability).abs < 10.0)
    ()
  }

  "Picked item" should "be distributed based on probabilities" in {
    val counters = Array(0, 0)
    1 to 300 foreach (i ⇒ {
      val index = GetWithProbability.choseOne(probabilities)
      counters(index) = counters(index) + 1
    })
    0 to probabilities.length - 1 foreach (i ⇒ {
      println(s"probability ${probabilities(i)} - counter ${counters(i)}")
      assert((3 * probabilities(i) - counters(i)).abs < 10.0)
      ()
    })
  }
}

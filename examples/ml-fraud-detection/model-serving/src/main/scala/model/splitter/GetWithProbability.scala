package model.splitter

import util.control.Breaks._

object GetWithProbability {

  val rundom = new scala.util.Random

  /**
   * This method check wheather to use a value based on probability
   * @param probability of using data.
   * @return boolean specifying whether to use number
   */
  def toUse(probability: Int): Boolean = rundom.nextInt(100) <= probability

  /**
   * This method decides which element to use based on probabilities array.
   * If the sum of probabilities is less then 100, this array has to be sorted by
   * probabilities - largest last
   * The sum of elements in the probabilities array has to be less or equal to 100
   * If the sum is less then 100, then the largest probability is increased to make
   * sum equal to 100.
   * @param probabilities of using data.
   * @return boolean specifying whether to use number
   */
  def choseOne(probabilities: Array[Int]): Int = {
    val boundaries = new Array[Int](probabilities.length)
    0 to probabilities.length - 1 foreach (index ⇒ {
      index match {
        case i if i == 0 ⇒ boundaries(i) = probabilities(i)
        case _           ⇒ boundaries(index) = boundaries(index - 1) + probabilities(index)
      }
    })
    boundaries(probabilities.length - 1) = 100
    val value = rundom.nextInt(100)
    var index = 0
    breakable {
      0 to boundaries.length - 1 foreach (i ⇒ {
        if (value < boundaries(i)) {
          index = i
          break
        }
      })
    }
    index
  }
}

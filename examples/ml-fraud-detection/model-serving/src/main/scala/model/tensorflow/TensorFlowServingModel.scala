package model.tensorflow

import model.ModelDescriptorUtil.implicits._
import com.google.gson.Gson
import model.ModelBase
import modelserving.model.ModelDescriptor
import scalaj.http.Http

/**
 * Abstract class for any TensorFlow serving model processing. It has to be extended by the user to
 * implement supporting methods (data transforms), based on his own model. Serializability here is required for Spark.
 */

abstract class TensorFlowServingModel[INRECORD, MODEL_OUTPUT, HTTPREQUEST, HTTPRESULT](
    descriptor: ModelDescriptor)(makeDefaultModelOutput: () ⇒ MODEL_OUTPUT)
  extends ModelBase[INRECORD, MODEL_OUTPUT](descriptor)(makeDefaultModelOutput) with Serializable {

  assert(descriptor.modelSourceLocation != None, s"Invalid descriptor ${descriptor.toRichString}")

  val gson = new Gson
  val clazz: Class[HTTPRESULT]

  // Convert input into file path
  val path = descriptor.modelSourceLocation.get

  /** Convert incoming request to HTTP */
  def getHTTPRequest(input: INRECORD): HTTPREQUEST

  /** Convert HTTP Result to model output */
  def getModelOutput(input: INRECORD, output: HTTPRESULT): MODEL_OUTPUT

  /**
   * Do implementation-specific scoring. On failure, an error string is returned in
   * the left. This will mean that the `MODEL_OUTPUT` will be initialized downstream
   * with default values and won't be meaningful!
   *
   * @param input record to score.
   * @return either a Left with any errors as a string or a Right with the output of invoking the model.
   */
  override protected def invokeModel(input: INRECORD): Either[String, MODEL_OUTPUT] = {
    // Post request
    try {
      val result = Http(path).postData(gson.toJson(getHTTPRequest(input))).header("content-type", "application/json").asString
      val prediction = gson.fromJson(result.body, clazz)
      result.code match {
        case 200 ⇒ // Success
          Right(getModelOutput(input, prediction))
        case _ ⇒ // Error
          Left(s"Error processing serving request - code ${result.code}, error ${result.body}")
      }
    } catch {
      case t: Throwable ⇒
        Left(t.getMessage)
    }
  }
}

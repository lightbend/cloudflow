package pipelines.examples.frauddetection.utils

import pipelines.examples.frauddetection.data.CustomerTransaction
import spray.json.{ DefaultJsonProtocol, JsNumber, JsObject, JsString, JsValue, RootJsonFormat }

case object CustomerTransactionProtocol extends DefaultJsonProtocol {

  implicit object CustomerTransactionFormat extends RootJsonFormat[CustomerTransaction] {
    def write(tx: CustomerTransaction) = JsObject(
      "time" -> JsNumber(tx.time),
      "v1" -> JsNumber(tx.v1),
      "v2" -> JsNumber(tx.v2),
      "v3" -> JsNumber(tx.v3),
      "v4" -> JsNumber(tx.v4),
      "v5" -> JsNumber(tx.v5),
      "v6" -> JsNumber(tx.v6),
      "v7" -> JsNumber(tx.v7),
      "v9" -> JsNumber(tx.v9),
      "v10" -> JsNumber(tx.v10),
      "v11" -> JsNumber(tx.v11),
      "v12" -> JsNumber(tx.v12),
      "v14" -> JsNumber(tx.v14),
      "v16" -> JsNumber(tx.v16),
      "v17" -> JsNumber(tx.v17),
      "v18" -> JsNumber(tx.v18),
      "v19" -> JsNumber(tx.v19),
      "v21" -> JsNumber(tx.v21),
      "amount" -> JsNumber(tx.amount),
      "transactionId" -> JsString(tx.transactionId),
      "customerId" -> JsString(tx.customerId),
      "merchantId" -> JsString(tx.merchantId)
    )

    def read(json: JsValue) = {
      val fields = json.asJsObject.fields
      CustomerTransaction(
        fields("time").convertTo[Long],
        fields("v1").convertTo[Float],
        fields("v2").convertTo[Float],
        fields("v3").convertTo[Float],
        fields("v4").convertTo[Float],
        fields("v5").convertTo[Float],
        fields("v6").convertTo[Float],
        fields("v7").convertTo[Float],
        fields("v9").convertTo[Float],
        fields("v10").convertTo[Float],
        fields("v11").convertTo[Float],
        fields("v12").convertTo[Float],
        fields("v14").convertTo[Float],
        fields("v16").convertTo[Float],
        fields("v17").convertTo[Float],
        fields("v18").convertTo[Float],
        fields("v19").convertTo[Float],
        fields("v21").convertTo[Float],
        fields("amount").convertTo[Float],
        fields("transactionId").convertTo[String],
        fields("customerId").convertTo[String],
        fields("merchantId").convertTo[String]
      )
    }
  }
}


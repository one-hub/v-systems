package vsys.api.http.database

import io.swagger.annotations.ApiModelProperty
import play.api.libs.json.{Format, Json}
import vsys.account.PublicKeyAccount
import vsys.api.http.BroadcastRequest
import vsys.blockchain.transaction.TransactionParser.SignatureStringLength
import vsys.blockchain.transaction.ValidationError
import vsys.blockchain.transaction.database.DbPutTransaction

case class SignedDbPutRequest(@ApiModelProperty(value = "Base58 encoded sender public key", required = true)
                                     senderPublicKey: String,
                                     @ApiModelProperty(value = "name", required = true)
                                     dbKey: String,
                                     @ApiModelProperty(value = "dataType", required = true)
                                     dataType: String,
                                     @ApiModelProperty(value = "data")
                                     data: String,
                                     @ApiModelProperty(required = true)
                                     fee: Long,
                                     @ApiModelProperty(required = true)
                                     feeScale: Short,
                                     @ApiModelProperty(required = true)
                                     timestamp: Long,
                                     @ApiModelProperty(required = true)
                                     signature: String) extends BroadcastRequest {
  def toTx: Either[ValidationError, DbPutTransaction] = for {
    _sender <- PublicKeyAccount.fromBase58String(senderPublicKey)
    _signature <- parseBase58(signature, "invalid.signature", SignatureStringLength)
    _t <- DbPutTransaction.create(_sender, dbKey, dataType, data, fee, feeScale, timestamp, _signature)
  } yield _t
}

object SignedDbPutRequest {
  implicit val broadcastDbPutRequestReadsFormat: Format[SignedDbPutRequest] = Json.format
}

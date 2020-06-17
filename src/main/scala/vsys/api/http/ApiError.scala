package vsys.api.http

import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import play.api.libs.json._
import vsys.blockchain.state.diffs.TransactionDiffer.TransactionValidationError
import vsys.blockchain.transaction.{Transaction, ValidationError}

case class ApiErrorResponse(error: Int, message: String)

object ApiErrorResponse {
  implicit val toFormat: Reads[ApiErrorResponse] = Json.reads[ApiErrorResponse]
}

trait ApiError {
  val id: Int
  val message: String
  val code: StatusCode

  lazy val json = Json.obj("error" -> id, "message" -> message)
}


object ApiError {
  def fromValidationError(e: ValidationError): ApiError = e match {
    case ValidationError.InvalidAddress => InvalidAddress
    case ValidationError.NegativeAmount => NegativeAmount
    case ValidationError.InsufficientFee => InsufficientFee
    case ValidationError.InvalidName => InvalidName
    case ValidationError.InvalidContract => InvalidContract
    case ValidationError.InvalidDataEntry => InvalidDataEntry
    case ValidationError.InvalidDataLength => InvalidDataLength
    case ValidationError.InvalidContractAddress => InvalidContractAddress
    case ValidationError.InvalidDbKey => InvalidDbKey
    case ValidationError.InvalidSignature(_, _) => InvalidSignature
    case ValidationError.InvalidRequestSignature => InvalidSignature
    case ValidationError.InvalidProofBytes => InvalidProofBytes
    case ValidationError.InvalidProofLength => InvalidProofLength
    case ValidationError.InvalidProofType => InvalidProofType
    case ValidationError.TooBigArray => TooBigArrayAllocation
    case ValidationError.OverflowError => OverflowError
    case ValidationError.ToSelf => ToSelfError
    case ValidationError.MissingSenderPrivateKey => MissingSenderPrivateKey
    case ValidationError.GenericError(ge) => CustomValidationError(ge)
    case ValidationError.UnsupportedTransactionType => CustomValidationError("UnsupportedTransactionType")
    case ValidationError.AccountBalanceError(errs) => CustomValidationError(errs.values.mkString(", "))
    case ValidationError.Mistiming(err) => Mistiming(err)
    case ValidationError.DbDataTypeError(err) => InvalidDbDataType(err)
    case ValidationError.WrongFeeScale(errFeeScale) => InvalidFeeScale(errFeeScale)
    case ValidationError.InvalidSlotId(errSlotId) => InvalidSlotId(errSlotId)
    case ValidationError.WrongMintingReward(errMintingReward) => InvalidMintingReward(errMintingReward)
    case ValidationError.TooLongDbEntry(actualLength, maxLength) => TooLongDbEntry(actualLength, maxLength)
    case ValidationError.InvalidUTF8String(field) => InvalidUTF8String(field)
    case TransactionValidationError(error, tx) => error match {
      case ValidationError.Mistiming(errorMessage) => Mistiming(errorMessage)
      case _ => StateCheckFailed(tx, fromValidationError(error).message)
    }
  }
}

case object Unknown extends ApiError {
  override val id = 0
  override val code = StatusCodes.InternalServerError
  override val message = "Error is unknown"
}

case class WrongJson(
                        cause: Option[Throwable] = None,
                        errors: Seq[(JsPath, Seq[JsonValidationError])] = Seq.empty) extends ApiError {
  override val id = 1
  override val code = StatusCodes.BadRequest
  override lazy val message = "failed to parse json message"
  override lazy val json: JsObject = Json.obj(
    "error" -> id,
    "message" -> message,
    "cause" -> cause.map(_.toString),
    "validationErrors" -> JsError.toJson(errors)
  )
}

//API Auth
case object ApiKeyNotValid extends ApiError {
  override val id = 2
  override val code = StatusCodes.Forbidden
  override val message: String = "Provided API key is not correct"
}

case object TooBigArrayAllocation extends ApiError {
  override val id: Int = 10
  override val message: String = "Too big sequences requested"
  override val code: StatusCode = StatusCodes.BadRequest
}


//VALIDATION
case object InvalidSignature extends ApiError {
  override val id = 101
  override val code = StatusCodes.BadRequest
  override val message = "invalid signature"
}

case object InvalidAddress extends ApiError {
  override val id = 102
  override val code = StatusCodes.BadRequest
  override val message = "invalid address"
}

case object InvalidSeed extends ApiError {
  override val id = 103
  override val code = StatusCodes.BadRequest
  override val message = "invalid seed"
}

case object InvalidAmount extends ApiError {
  override val id = 104
  override val code = StatusCodes.BadRequest
  override val message = "invalid amount"
}

case object InvalidFee extends ApiError {
  override val id = 105
  override val code = StatusCodes.BadRequest
  override val message = "invalid fee"
}

case object InvalidSender extends ApiError {
  override val id = 106
  override val code = StatusCodes.BadRequest
  override val message = "invalid sender"
}

case object InvalidRecipient extends ApiError {
  override val id = 107
  override val code = StatusCodes.BadRequest
  override val message = "invalid recipient"
}

case object InvalidPublicKey extends ApiError {
  override val id = 108
  override val code = StatusCodes.BadRequest
  override val message = "invalid public key"
}

case object InvalidNotNumber extends ApiError {
  override val id = 109
  override val code = StatusCodes.BadRequest
  override val message = "argument is not a number"
}

case object InvalidMessage extends ApiError {
  override val id = 110
  override val code = StatusCodes.BadRequest
  override val message = "invalid message"
}

case object InvalidName extends ApiError {
  override val id: Int = 111
  override val message: String = "invalid name"
  override val code: StatusCode = StatusCodes.BadRequest
}

case class StateCheckFailed(tx: Transaction, err: String) extends ApiError {
  override val id: Int = 112
  override val message: String = s"State check failed. Reason: $err"
  override val code: StatusCode = StatusCodes.BadRequest
  override lazy val json = Json.obj("error" -> id, "message" -> message, "tx" -> tx.json)
}

case object OverflowError extends ApiError {
  override val id: Int = 113
  override val message: String = "overflow error"
  override val code: StatusCode = StatusCodes.BadRequest
}

case object ToSelfError extends ApiError {
  override val id: Int = 114
  override val message: String = "Transaction to yourself"
  override val code: StatusCode = StatusCodes.BadRequest
}

case object MissingSenderPrivateKey extends ApiError {
  override val id: Int = 115
  override val message: String = "no private key for sender address in wallet"
  override val code: StatusCode = StatusCodes.BadRequest
}

case object InvalidDbKey extends ApiError {
  override val id: Int = 117
  override val message: String = "invalid db key"
  override val code: StatusCode = StatusCodes.BadRequest
}

case class InvalidUTF8String(field: String) extends ApiError {
  override val id: Int = 118
  override val code = StatusCodes.BadRequest
  override val message: String = s"The $field is not a valid utf8 string"
}

case object InvalidProofBytes extends ApiError {
  override val id: Int = 119
  override val code = StatusCodes.BadRequest
  override val message: String = "Invalid Proof Bytes"
}

case object InvalidProofLength extends ApiError {
  override val id: Int = 120
  override val code = StatusCodes.BadRequest
  override val message: String = "Invalid Proof Length"
}

case object InvalidProofType extends ApiError {
  override val id: Int = 121
  override val code = StatusCodes.BadRequest
  override val message: String = "Invalid Proof Type"
}

case object InvalidContract extends ApiError {
  override val id: Int = 122
  override val code = StatusCodes.BadRequest
  override val message: String = "Invalid Contract"
}

case object InvalidDataEntry extends ApiError {
  override val id: Int = 123
  override val message: String = "Invalid DataEntry"
  override val code: StatusCode = StatusCodes.BadRequest
}

case object InvalidDataLength extends ApiError {
  override val id: Int = 124
  override val message: String = "Invalid DataEntry Length"
  override val code: StatusCode = StatusCodes.BadRequest
}

case object InvalidContractAddress extends ApiError {
  override val id: Int = 125
  override val message: String = "Invalid Contract Address"
  override val code: StatusCode = StatusCodes.BadRequest
}

case object InvalidTokenIndex extends ApiError {
  override val id: Int = 126
  override val message: String = "Invalid Token Index"
  override val code: StatusCode = StatusCodes.BadRequest
}

case class CustomValidationError(errorMessage: String) extends ApiError {
  override val id: Int = 199
  override val message: String = errorMessage
  override val code: StatusCode = StatusCodes.BadRequest
}

case object BlockNotExists extends ApiError {
  override val id: Int = 301
  override val code = StatusCodes.NotFound
  override val message: String = "block does not exist"
}

case object ContractNotExists extends ApiError {
  override val id: Int = 401
  override val code = StatusCodes.NotFound
  override val message: String = "Contract is not in blockchain"
}

case object TokenNotExists extends ApiError {
  override val id: Int = 402
  override val code = StatusCodes.NotFound
  override val message: String = "Token is not in blockchain"
}

case class ContractAlreadyDisabled(name: String) extends ApiError {
  override val id: Int = 403
  override val code = StatusCodes.BadRequest
  override val message: String = s"contract $name already disabled"
}

case class invalidDbNameSpace(nameSpace: String) extends ApiError {
  override val id: Int = 501
  override val code = StatusCodes.BadRequest
  override val message: String = s"nameSpace $nameSpace is not valid"
}

case class dbEntryNotExist(name: String, nameSpace: String) extends ApiError {
  override val id: Int = 502
  override val code = StatusCodes.BadRequest
  override val message: String = s"the entry for $name does not exist for the nameSpace $nameSpace"
}

case object invalidDbEntry extends ApiError {
  override val id: Int = 503
  override val code = StatusCodes.BadRequest
  override val message: String = s"invalid database entry, this entry might be corruptted"
}

case class InvalidDbDataType(datatype: String) extends ApiError {
  override val id: Int = 504
  override val code = StatusCodes.BadRequest
  override val message: String = s"invalid database datatype $datatype"
}

case class TooLongDbEntry(actualLength: Int, maxLength: Int) extends ApiError {
  override val id: Int = 505
  override val code = StatusCodes.BadRequest
  override val message: String = s"The data is too long for database put, the actual length " +
    s"is $actualLength, the maximun length supported for now is $maxLength"
}

case class Mistiming(errorMessage: String) extends ApiError {
  override val id: Int = Mistiming.Id
  override val message: String = errorMessage
  override val code: StatusCode = StatusCodes.BadRequest
}

object Mistiming {
  val Id = 303
}

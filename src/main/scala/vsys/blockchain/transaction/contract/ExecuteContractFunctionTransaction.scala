package vsys.blockchain.transaction.contract

import com.google.common.primitives.{Bytes, Longs, Shorts}
import play.api.libs.json.{JsObject, Json}
import scorex.crypto.encode.Base58
import vsys.account.{ContractAccount, PrivateKeyAccount, PublicKeyAccount}
import vsys.blockchain.contract.DataEntry
import vsys.blockchain.state.ByteStr
import vsys.blockchain.transaction.TransactionParser._
import vsys.blockchain.transaction._
import vsys.blockchain.transaction.proof._
import vsys.utils.base58Length
import vsys.utils.serialization.{BytesSerializable, Deser}

import scala.util.{Failure, Success, Try}

case class ExecuteContractFunctionTransaction private(contractId: ContractAccount,
                                                      funcIdx: Short,
                                                      data: Seq[DataEntry],
                                                      attachment: Array[Byte],
                                                      transactionFee: Long,
                                                      feeScale: Short,
                                                      timestamp: Long,
                                                      proofs: Proofs) extends ProvenTransaction {

  val transactionType = TransactionType.ExecuteContractFunctionTransaction

  lazy val toSign: Array[Byte] = Bytes.concat(
    Array(transactionType.id.toByte),
    contractId.bytes.arr,
    Shorts.toByteArray(funcIdx),
    Deser.serializeArray(DataEntry.serializeArrays(data)),
    BytesSerializable.arrayWithSize(attachment),
    Longs.toByteArray(transactionFee),
    Shorts.toByteArray(feeScale),
    Longs.toByteArray(timestamp)
  )

  override lazy val json: JsObject = jsonBase() ++ Json.obj(
    "contractId" -> contractId.address,
    "functionIndex" -> funcIdx,
    "functionData" -> Base58.encode(DataEntry.serializeArrays(data)),
    "attachment" -> Base58.encode(attachment),
    "timestamp" -> timestamp
  )

  override lazy val bytes: Array[Byte] = Bytes.concat(toSign, proofs.bytes)

}

object ExecuteContractFunctionTransaction extends TransactionParser {

  val MaxDescriptionSize = 140
  val maxDescriptionStringSize: Int = base58Length(MaxDescriptionSize)

  def parseTail(bytes: Array[Byte]): Try[ExecuteContractFunctionTransaction] = Try {
    (for {
      contractId <- ContractAccount.fromBytes(bytes.slice(0, ContractAccount.AddressLength))
      funcIdx = Shorts.fromByteArray(bytes.slice(ContractAccount.AddressLength, ContractAccount.AddressLength + 2))
      (dataBytes, dataEnd) = Deser.parseArraySize(bytes, ContractAccount.AddressLength + 2)
      data <- DataEntry.parseArrays(dataBytes)
      (description, descriptionEnd) = Deser.parseArraySize(bytes, dataEnd)
      fee = Longs.fromByteArray(bytes.slice(descriptionEnd, descriptionEnd + 8))
      feeScale = Shorts.fromByteArray(bytes.slice(descriptionEnd + 8, descriptionEnd + 10))
      timestamp = Longs.fromByteArray(bytes.slice(descriptionEnd + 10, descriptionEnd + 18))
      proofs <- Proofs.fromBytes(bytes.slice(descriptionEnd + 18, bytes.length))
      tx <- ExecuteContractFunctionTransaction.createWithProof(contractId, funcIdx,
        data, description, fee, feeScale, timestamp, proofs)
    } yield tx).fold(left => Failure(new Exception(left.toString)), right => Success(right))
  }.flatten

  def createWithProof(contractId: ContractAccount,
                      funcIdx: Short,
                      data: Seq[DataEntry],
                      attachment: Array[Byte],
                      fee: Long,
                      feeScale: Short,
                      timestamp: Long,
                      proofs: Proofs): Either[ValidationError, ExecuteContractFunctionTransaction] =
    if (attachment.length > MaxDescriptionSize) {
      Left(ValidationError.TooBigArray)
    } else if (fee <= 0) {
      Left(ValidationError.InsufficientFee)
    } else if (feeScale != DefaultFeeScale) {
      Left(ValidationError.WrongFeeScale(feeScale))
    } else {
      Right(ExecuteContractFunctionTransaction(contractId, funcIdx, data, attachment, fee, feeScale, timestamp, proofs))
    }

  def create(sender: PrivateKeyAccount,
             contractId: ContractAccount,
             funcIdx: Short,
             data: Seq[DataEntry],
             attachment: Array[Byte],
             fee: Long,
             feeScale: Short,
             timestamp: Long): Either[ValidationError, ExecuteContractFunctionTransaction] = for {
    unsigned <- createWithProof(contractId, funcIdx, data, attachment, fee, feeScale, timestamp, Proofs.empty)
    proofs <- Proofs.create(List(EllipticCurve25519Proof.createProof(unsigned.toSign, sender).bytes))
    tx <- createWithProof(contractId, funcIdx, data, attachment, fee, feeScale, timestamp, proofs)
  } yield tx

  def create(sender: PublicKeyAccount,
             contractId: ContractAccount,
             funcIdx: Short,
             data: Seq[DataEntry],
             description: Array[Byte],
             fee: Long,
             feeScale: Short,
             timestamp: Long,
             signature: ByteStr): Either[ValidationError, ExecuteContractFunctionTransaction] = for {
    proofs <- Proofs.create(List(EllipticCurve25519Proof.buildProof(sender, signature).bytes))
    tx <- createWithProof(contractId, funcIdx, data, description, fee, feeScale, timestamp, proofs)
  } yield tx
}

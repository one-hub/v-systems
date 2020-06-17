package vsys.blockchain.transaction.database

import com.google.common.primitives.{Bytes, Longs, Shorts}
import play.api.libs.json.{JsObject, Json}
import vsys.account.{Address, PrivateKeyAccount, PublicKeyAccount}
import vsys.blockchain.database.{DataType, Entry}
import vsys.blockchain.state.ByteStr
import vsys.blockchain.transaction._
import vsys.blockchain.transaction.TransactionParser._
import vsys.blockchain.transaction.ValidationError.DbDataTypeError
import vsys.blockchain.transaction.proof.{EllipticCurve25519Proof, Proofs}
import vsys.utils.serialization.{BytesSerializable, Deser}

import scala.util.{Failure, Success, Try}

case class DbPutTransaction private(dbKey: String,
                                    entry: Entry,
                                    transactionFee: Long,
                                    feeScale: Short,
                                    timestamp: Long,
                                    proofs: Proofs) extends ProvenTransaction {

  val transactionType = TransactionType.DbPutTransaction

  lazy val toSign: Array[Byte] = Bytes.concat(
    Array(transactionType.id.toByte),
    BytesSerializable.arrayWithSize(Deser.serilizeString(dbKey)),
    BytesSerializable.arrayWithSize(entry.bytes.arr),
    Longs.toByteArray(transactionFee),
    Shorts.toByteArray(feeScale),
    Longs.toByteArray(timestamp))

  override lazy val json: JsObject = jsonBase() ++ Json.obj(
    "dbKey" -> dbKey,
    "entry" -> entry.json,
    "timestamp" -> timestamp
  )
  lazy val publicKey: PublicKeyAccount = EllipticCurve25519Proof.fromBytes(proofs.proofs.head.bytes.arr).toOption.get.publicKey
  lazy val storageKey: ByteStr = DbPutTransaction.generateKey(publicKey.toAddress, dbKey)
  override lazy val bytes: Array[Byte] = Bytes.concat(toSign, proofs.bytes)

}

object DbPutTransaction extends TransactionParser {

  val MaxDbKeyLength = 30
  val MinDbKeyLength = 1

  def generateKey(owner: Address, key: String):ByteStr =
    ByteStr(owner.bytes.arr ++ Deser.serilizeString(key))

  def parseTail(bytes: Array[Byte]): Try[DbPutTransaction] = Try {
    val (dbKeyBytes, dbKeyEnd) = Deser.parseArraySize(bytes, 0)
    val dbKey: String = Deser.deserilizeString(dbKeyBytes)
    val (dbEntryBytes, dbEntryEnd) = Deser.parseArraySize(bytes, dbKeyEnd)
    (for {
      dbEntry <- Entry.fromBytes(dbEntryBytes)
      fee = Longs.fromByteArray(bytes.slice(dbEntryEnd, dbEntryEnd + 8))
      feeScale = Shorts.fromByteArray(bytes.slice(dbEntryEnd + 8, dbEntryEnd + 10))
      timestamp = Longs.fromByteArray(bytes.slice(dbEntryEnd + 10, dbEntryEnd + 18))
      proofs <- Proofs.fromBytes(bytes.slice(dbEntryEnd + 18, bytes.length))
      tx <- DbPutTransaction.create(dbKey, dbEntry, fee, feeScale, timestamp, proofs)
    } yield tx).fold(left => Failure(new Exception(left.toString)), right => Success(right))
  }.flatten

  def create(dbKey: String,
             dbEntry: Entry,
             fee: Long,
             feeScale: Short,
             timestamp: Long,
             proofs: Proofs): Either[ValidationError, DbPutTransaction] =
    if (dbKey.length > MaxDbKeyLength || dbKey.length < MinDbKeyLength) {
      Left(ValidationError.InvalidDbKey)
    } else if (!Deser.validUTF8(dbKey)) {
      Left(ValidationError.InvalidUTF8String("dbKey"))
    } else if(fee <= 0) {
      Left(ValidationError.InsufficientFee)
    } else if (feeScale != DefaultFeeScale) {
      Left(ValidationError.WrongFeeScale(feeScale))
    } else {
      Right(DbPutTransaction(dbKey, dbEntry, fee, feeScale, timestamp, proofs))
    }

  def create(sender: PublicKeyAccount,
             dbKey: String,
             dbEntry: Entry,
             fee: Long,
             feeScale: Short,
             timestamp: Long,
             signature: ByteStr): Either[ValidationError, DbPutTransaction] =
    for {
      proofs <- Proofs.create(List(EllipticCurve25519Proof.buildProof(sender, signature).bytes))
      tx <- create(dbKey, dbEntry, fee, feeScale, timestamp, proofs)
    } yield tx

  def create(sender: PublicKeyAccount,
             dbKey: String,
             dbDataType: String,
             dbData: String,
             fee: Long,
             feeScale: Short,
             timestamp: Long,
             signature: ByteStr): Either[ValidationError, DbPutTransaction] =
    for {
      datatype <- DataType.values.find(_.toString == dbDataType) match {
          case Some(x) => Right(x)
          case None =>Left(DbDataTypeError(dbDataType))
      }
      dbEntry <- Entry.buildEntry(dbData, datatype)
      tx <- create(sender, dbKey, dbEntry, fee, feeScale, timestamp, signature)
    } yield tx

  def create(sender: PrivateKeyAccount,
             dbKey: String,
             dbEntry: Entry,
             fee: Long,
             feeScale: Short,
             timestamp: Long): Either[ValidationError, DbPutTransaction] =
  for {
    unsigned <- create(dbKey, dbEntry, fee, feeScale, timestamp, Proofs.empty)
    proofs <- Proofs.create(List(EllipticCurve25519Proof.createProof(unsigned.toSign, sender).bytes))
    tx <- create(dbKey, dbEntry, fee, feeScale, timestamp, proofs)
  } yield tx

  def create(sender: PrivateKeyAccount,
             dbKey: String,
             dbDataType: String,
             dbData: String,
             fee: Long,
             feeScale: Short,
             timestamp: Long): Either[ValidationError, DbPutTransaction] =
  for {
    datatype <- DataType.values.find(_.toString == dbDataType) match {
      case Some(x) => Right(x)
      case None =>Left(DbDataTypeError(dbDataType))
    }
    dbEntry <- Entry.buildEntry(dbData, datatype)
    tx <- create(sender, dbKey, dbEntry, fee, feeScale, timestamp)
  } yield tx
}
